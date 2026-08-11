/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.driver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Filesystem side of {@link AtlasGenerator}: swapping the two owned output roots for a run's staged
 * trees, and discarding the staging directory afterwards.
 *
 * <p>Split out of {@code AtlasGenerator} so the generator reads as compile-and-collect while the
 * rename plan, its rollback, and the best-effort cleanup live together — they are one concern
 * (nothing here knows about javac, diagnostics, or artifact kinds) and they are what has to be
 * reasoned about when a run fails midway.
 */
final class OutputPublisher {

    private OutputPublisher() {
    }

    /**
     * Replaces both owned roots with this run's staged trees as a unit. The previous trees are moved
     * aside into {@code backup} rather than deleted, so if either replacement fails they can be put
     * back — the alternative leaves the caller with this run's sources beside the last run's
     * resources. {@code backup} lives inside the staging directory, so the superseded trees are
     * discarded with it once the run succeeds.
     */
    static void publish(Path stagedSources, Path stagedResources,
                        Path sourceOut, Path resourceOut, Path backup) throws IOException {
        List<Move> plan = List.of(
                new Move(sourceOut, backup.resolve(AtlasGenerator.SOURCES_DIR)),
                new Move(resourceOut, backup.resolve(AtlasGenerator.RESOURCES_DIR)),
                new Move(stagedSources, sourceOut),
                new Move(stagedResources, resourceOut));
        Deque<Move> done = new ArrayDeque<>();
        try {
            for (Move move : plan) {
                if (moveIfExists(move)) {
                    done.push(move.reversed());
                }
            }
        } catch (IOException e) {
            rollback(done, e);
            throw e;
        }
    }

    /** Renames {@code move.from()} when it is there; reports whether anything moved. */
    private static boolean moveIfExists(Move move) throws IOException {
        if (!Files.exists(move.from())) {
            return false;
        }
        Files.createDirectories(move.to().getParent());
        Files.move(move.from(), move.to());
        return true;
    }

    /**
     * Undoes the renames already applied, newest first. A rename that cannot be undone is attached
     * to the failure being reported rather than replacing it.
     */
    private static void rollback(Deque<Move> done, IOException failure) {
        while (!done.isEmpty()) {
            Move move = done.pop();
            try {
                Files.move(move.from(), move.to());
            } catch (IOException e) {
                failure.addSuppressed(e);
            }
        }
    }

    /** One rename in a publication plan. */
    private record Move(Path from, Path to) {

        Move reversed() {
            return new Move(to, from);
        }
    }

    /**
     * Removes the staging directory. Runs in a {@code finally} block, so a path that cannot be
     * removed is deferred to JVM exit rather than masking the real failure — and the rest of the
     * tree is still deleted now, since one undeletable file must not strand its siblings.
     *
     * <p>Entries are deleted inline in post-order — no buffering of the full tree — so a traversal
     * failure mid-tree still removes every entry that was already visited. Per-entry deletion
     * failures are collected and deferred to {@code deleteOnExit}; only those failures (not the
     * whole tree) are buffered.
     */
    static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        List<Path> deferred = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!tryDelete(file)) {
                        deferred.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (exc != null) {
                        deferred.add(dir);
                        return FileVisitResult.CONTINUE;
                    }
                    if (!tryDelete(dir)) {
                        deferred.add(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                private boolean tryDelete(Path path) {
                    try {
                        return Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        return false;
                    }
                }
            });
        } catch (IOException | UncheckedIOException e) {
            deferred.add(root);
        }
        // deleteOnExit deletes in reverse registration order, and a directory only goes away once
        // it is empty — so register shallowest-first to have the JVM delete children first.
        deferred.sort(Comparator.naturalOrder());
        deferred.forEach(path -> path.toFile().deleteOnExit());
    }
}
