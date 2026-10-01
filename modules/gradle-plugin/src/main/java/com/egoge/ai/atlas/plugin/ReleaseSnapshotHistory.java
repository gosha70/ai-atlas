/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.plugin.ReleaseSnapshots.ReleaseException;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.IrJson;
import com.egoge.ai.atlas.processor.release.ReleaseChangelog;
import com.egoge.ai.atlas.processor.release.ReleaseManifest;
import com.egoge.ai.atlas.processor.release.ReleasePolicy;
import com.egoge.ai.atlas.processor.release.ReleaseVersion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Reads the release directories {@link ReleaseSnapshots} writes: the verified history, and the
 * aggregate changelog, extracted from {@link ReleaseSnapshots} so each file stays under the
 * 500-line cap.
 */
public final class ReleaseSnapshotHistory {

    private static final String RESTORE = " Released contracts are immutable: restore it from version control.";

    private ReleaseSnapshotHistory() {
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
            ContractIr ir = parse(Files.readString(dir.resolve(ReleaseSnapshots.IR_FILE), StandardCharsets.UTF_8),
                    dir.resolve(ReleaseSnapshots.IR_FILE).toString());
            if (ir.apiMajor() != manifest.apiMajor()) {
                throw new ReleaseException("Release manifest " + dir.resolve(ReleaseSnapshots.MANIFEST_FILE)
                        + " records apiMajor " + manifest.apiMajor() + ", but its " + ReleaseSnapshots.IR_FILE
                        + " has apiMajor " + ir.apiMajor() + "." + RESTORE);
            }
            // Not the real publication verdict: this reader does not touch git. ReleaseSnapshots
            // remaps each entry's published flag from PublishedHistory's tag-proved verdict (F1,
            // D10.1) before using this list for anything that depends on it.
            result.add(new ReleasePolicy.Release(version, ir, false));
        }
        return result;
    }

    /**
     * Verifies only the internal consistency of the committed release snapshots (E1, AC13, AC14):
     * no git, no network. Beyond what {@link #history} already verifies (every directory against
     * its manifest, including digests and extra or missing files), this also checks that each
     * manifest's {@code previous} equals the preceding directory's version (the first being {@code
     * null}), and that {@code changelog} equals {@link #aggregateChangelog} byte for byte. With no
     * release directory and no changelog file, this succeeds silently.
     *
     * @param releases  the directory of releases, which may not exist
     * @param changelog the aggregate changelog to check
     * @return the number of releases verified
     * @throws ReleaseException if a release fails verification, the {@code previous} chain is
     *                          broken, or the changelog does not match
     * @throws IOException      if a file cannot be read
     */
    public static int checkConsistency(Path releases, Path changelog) throws ReleaseException, IOException {
        List<Path> dirs = releaseDirs(releases);
        history(releases); // verifies every directory's manifest, digests, and extra/missing files
        String previous = null;
        for (Path dir : dirs) {
            Path manifestFile = dir.resolve(ReleaseSnapshots.MANIFEST_FILE);
            ReleaseManifest manifest;
            try {
                manifest = ReleaseManifest.read(Files.readString(manifestFile, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new ReleaseException("Release manifest " + manifestFile + " cannot be read: " + e.getMessage()
                        + "." + RESTORE);
            }
            if (!Objects.equals(previous, manifest.previous())) {
                throw new ReleaseException("Release manifest " + manifestFile + " records previous " + manifest.previous()
                        + ", but the preceding release in " + releases + " is " + previous + ": the previous chain"
                        + " is broken." + RESTORE);
            }
            previous = manifest.version();
        }
        if (!dirs.isEmpty() || Files.isRegularFile(changelog)) {
            String expected = aggregateChangelog(releases);
            if (!Files.isRegularFile(changelog)) {
                throw new ReleaseException("The aggregate changelog " + changelog + " is missing, but release history"
                        + " exists under " + releases + ". Regenerate it with a release, or restore it from version"
                        + " control.");
            }
            String actual = Files.readString(changelog, StandardCharsets.UTF_8);
            if (!actual.equals(expected)) {
                throw new ReleaseException("The aggregate changelog " + changelog + " does not equal what the release"
                        + " history under " + releases + " would generate: it was edited after release." + RESTORE);
            }
        }
        return dirs.size();
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
            sections.add(Files.readString(dir.resolve(ReleaseSnapshots.CHANGELOG_FILE), StandardCharsets.UTF_8));
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
        Path manifestFile = dir.resolve(ReleaseSnapshots.MANIFEST_FILE);
        if (!Files.isRegularFile(manifestFile)) {
            throw new ReleaseException("Release " + dir + " has no " + ReleaseSnapshots.MANIFEST_FILE
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
        for (String required : List.of(ReleaseSnapshots.IR_FILE, ReleaseSnapshots.CHANGELOG_FILE)) {
            if (!manifest.digests().containsKey(required)) {
                throw new ReleaseException("Release manifest " + manifestFile + " records no digest of " + required
                        + "." + RESTORE);
            }
        }
        Set<String> present = new TreeSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.map(file -> file.getFileName().toString()).filter(ReleaseSnapshotHistory::isReleaseFile).forEach(present::add);
        }
        for (Map.Entry<String, String> entry : manifest.digests().entrySet()) {
            Path file = dir.resolve(entry.getKey());
            if (!Files.isRegularFile(file)) {
                throw new ReleaseException("Released file " + file + " was deleted after release." + RESTORE);
            }
            if (!ReleaseSnapshots.sha256(Files.readAllBytes(file)).equals(entry.getValue())) {
                throw new ReleaseException("Released file " + file + " was modified after release: its SHA-256 is not"
                        + " the one " + ReleaseSnapshots.MANIFEST_FILE + " records." + RESTORE);
            }
        }
        present.removeAll(manifest.digests().keySet());
        present.remove(ReleaseSnapshots.MANIFEST_FILE);
        if (!present.isEmpty()) {
            throw new ReleaseException("Release " + dir + " holds " + present + ", which " + ReleaseSnapshots.MANIFEST_FILE
                    + " does not record: a file was added after release. Remove it.");
        }
        return manifest;
    }

    private static ContractIr parse(String json, String source) throws ReleaseException {
        try {
            return IrJson.parse(json, source);
        } catch (IrJson.IrReadException e) {
            throw new ReleaseException(e.getMessage());
        }
    }

    /**
     * Whether a file in a release directory belongs to the release: every file but a dotfile, such
     * as a {@code .DS_Store} an operating system leaves there.
     *
     * @param name the file name
     * @return whether the release consists of it
     */
    static boolean isReleaseFile(String name) {
        return !name.startsWith(".");
    }
}
