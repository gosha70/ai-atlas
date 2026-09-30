/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.release;

import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.release.ContractRelease.ReleaseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Reads the release directories {@link ContractRelease} writes: the verified history, and the
 * aggregate changelog, extracted from {@link ContractRelease} so each file stays under the
 * 500-line cap.
 */
public final class ReleaseHistory {

    private static final String RESTORE = " Released contracts are immutable: restore it from version control.";

    private ReleaseHistory() {
    }

    /**
     * Every release in {@code releases}, oldest first, each verified: its manifest names this
     * release, every file it records has the recorded digest, and it holds no other file. A
     * directory whose name is not {@code MAJOR.MINOR.PATCH} is not a release and is skipped.
     *
     * @param releases the directory of releases, which may not exist
     * @return the releases, with their IR migrated in memory
     * @throws ReleaseException if a release fails verification
     * @throws IOException      if a file cannot be read
     */
    public static List<ReleasePolicy.Release> history(Path releases) throws ReleaseException, IOException {
        List<ReleasePolicy.Release> result = new ArrayList<>();
        for (Path dir : releaseDirs(releases)) {
            ReleaseVersion version = ReleaseVersion.parse(dir.getFileName().toString());
            ReleaseManifest manifest = verify(dir, version);
            ContractIr ir = parse(Files.readString(dir.resolve(ContractRelease.IR_FILE), StandardCharsets.UTF_8),
                    dir.resolve(ContractRelease.IR_FILE).toString());
            if (ir.apiMajor() != manifest.apiMajor()) {
                throw new ReleaseException("Release manifest " + dir.resolve(ContractRelease.MANIFEST_FILE)
                        + " records apiMajor " + manifest.apiMajor() + ", but its " + ContractRelease.IR_FILE
                        + " has apiMajor " + ir.apiMajor() + "." + RESTORE);
            }
            result.add(new ReleasePolicy.Release(version, ir));
        }
        return result;
    }

    /**
     * The aggregate changelog: every release's section, newest first.
     *
     * @param releases the directory of releases, which may not exist
     * @return the document
     * @throws IOException if a section cannot be read
     */
    public static String aggregateChangelog(Path releases) throws IOException {
        List<Path> dirs = new ArrayList<>(releaseDirs(releases));
        Collections.reverse(dirs);
        List<String> sections = new ArrayList<>();
        for (Path dir : dirs) {
            sections.add(Files.readString(dir.resolve(ContractRelease.CHANGELOG_FILE), StandardCharsets.UTF_8));
        }
        return ReleaseChangelog.aggregate(sections);
    }

    /** The release directories, oldest version first. */
    private static List<Path> releaseDirs(Path releases) throws IOException {
        if (!Files.isDirectory(releases)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(releases)) {
            return entries.filter(Files::isDirectory).filter(dir -> ReleaseVersion.matches(dir.getFileName().toString()))
                    .sorted(Comparator.comparing(dir -> ReleaseVersion.parse(dir.getFileName().toString())))
                    .toList();
        }
    }

    private static ReleaseManifest verify(Path dir, ReleaseVersion version) throws ReleaseException, IOException {
        Path manifestFile = dir.resolve(ContractRelease.MANIFEST_FILE);
        if (!Files.isRegularFile(manifestFile)) {
            throw new ReleaseException("Release " + dir + " has no " + ContractRelease.MANIFEST_FILE
                    + ", so it cannot be verified." + RESTORE);
        }
        ReleaseManifest manifest;
        try {
            manifest = ReleaseManifest.read(Files.readString(manifestFile, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new ReleaseException("Release manifest " + manifestFile + " cannot be read: " + e.getMessage() + "."
                    + RESTORE);
        }
        if (!version.toString().equals(manifest.version())) {
            throw new ReleaseException("Release manifest " + manifestFile + " records version " + manifest.version()
                    + ", not " + version + "." + RESTORE);
        }
        for (String required : List.of(ContractRelease.IR_FILE, ContractRelease.CHANGELOG_FILE)) {
            if (!manifest.digests().containsKey(required)) {
                throw new ReleaseException("Release manifest " + manifestFile + " records no digest of " + required
                        + "." + RESTORE);
            }
        }
        Set<String> present = new TreeSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.map(file -> file.getFileName().toString()).filter(name -> !name.startsWith(".")).forEach(present::add);
        }
        for (Map.Entry<String, String> entry : manifest.digests().entrySet()) {
            Path file = dir.resolve(entry.getKey());
            if (!Files.isRegularFile(file)) {
                throw new ReleaseException("Released file " + file + " was deleted after release." + RESTORE);
            }
            if (!ContractRelease.sha256(Files.readAllBytes(file)).equals(entry.getValue())) {
                throw new ReleaseException("Released file " + file + " was modified after release: its SHA-256 is not"
                        + " the one " + ContractRelease.MANIFEST_FILE + " records." + RESTORE);
            }
        }
        present.removeAll(manifest.digests().keySet());
        present.remove(ContractRelease.MANIFEST_FILE);
        if (!present.isEmpty()) {
            throw new ReleaseException("Release " + dir + " holds " + present + ", which " + ContractRelease.MANIFEST_FILE
                    + " does not record: a file was added after release. Remove it.");
        }
        return manifest;
    }

    private static ContractIr parse(String json, String source) throws ReleaseException {
        try {
            return com.egoge.ai.atlas.processor.contract.IrJson.parse(json, source);
        } catch (com.egoge.ai.atlas.processor.contract.IrJson.IrReadException e) {
            throw new ReleaseException(e.getMessage());
        }
    }
}
