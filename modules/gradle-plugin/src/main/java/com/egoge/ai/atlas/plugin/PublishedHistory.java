/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.release.ContractRelease;
import com.egoge.ai.atlas.processor.release.ReleaseManifest;
import com.egoge.ai.atlas.processor.release.ReleaseVersion;
import org.gradle.api.GradleException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * F1 (plan §3.4): whether the committed snapshots under a directory of releases are backed by
 * proved git tags, distinguishing a genuine first release from history that is merely
 * unavailable (the D10.5 table). Pure logic over {@link GitQuery}, the snapshot versions on disk
 * and a per-tag prover, so it is tested against a fake repository, never a real one.
 */
final class PublishedHistory {

    private static final String PREFIX = "[ai-atlas] ";
    private static final String FETCH_REMEDY = "Fetch tags and full history, e.g. actions/checkout with"
            + " fetch-depth: 0 and fetch-tags: true.";
    private static final String RELEASE_JSON = ContractRelease.MANIFEST_FILE;
    private static final String IR_JSON = ContractRelease.IR_FILE;

    private PublishedHistory() {
    }

    /** Whether a release is backed by a proved tag, or is part of the chain but earns no credit. */
    enum Status {
        PUBLISHED, PENDING
    }

    /** The publication status of every committed snapshot. */
    record Verdict(Map<ReleaseVersion, Status> statuses) {

        Verdict {
            statuses = Collections.unmodifiableMap(new LinkedHashMap<>(statuses));
        }

        /** The versions {@link Status#PUBLISHED}, so they may earn deprecation credit. */
        Set<ReleaseVersion> published() {
            Set<ReleaseVersion> result = new LinkedHashSet<>();
            statuses.forEach((version, status) -> {
                if (status == Status.PUBLISHED) {
                    result.add(version);
                }
            });
            return result;
        }
    }

    /**
     * The verdict on every committed snapshot under {@code releasesDir}.
     *
     * @param releasesDir    the directory of releases, which may not exist (a genuine first release)
     * @param releasesPrefix {@code releasesDir}'s path relative to the repository root
     *                       ({@link GitRepository#releasesPrefix}), forward-slashed and
     *                       {@code /}-terminated when non-empty
     * @param tagName        the configured tag name template
     * @param git            the repository, real or fake
     * @return the verdict
     * @throws GradleException if the published history needed for validation is unavailable, or a
     *                         tag fails its proof, each naming what is missing and the remedy
     * @throws IOException     if a release file cannot be read
     */
    static Verdict verify(Path releasesDir, String releasesPrefix, TagName tagName, GitQuery git) throws IOException {
        List<ReleaseVersion> versions = existingVersions(releasesDir);
        boolean repository = git.isInsideWorkTree();
        if (repository && git.isShallow()) {
            throw fail("The repository is a shallow clone, so it cannot establish a genuine first release nor prove"
                    + " published history, whatever the committed snapshots. " + FETCH_REMEDY);
        }
        List<String> tags = repository ? git.tags() : List.of();
        if (versions.isEmpty()) {
            failOnOrphanTag(releasesDir, tags, tagName, List.of());
            return new Verdict(Map.of());
        }
        if (!repository) {
            throw fail("Committed snapshots exist under " + releasesDir + ", but no git repository was found. "
                    + FETCH_REMEDY);
        }
        Map<ReleaseVersion, Status> result = new LinkedHashMap<>();
        for (int i = 0; i < versions.size(); i++) {
            ReleaseVersion version = versions.get(i);
            boolean newest = i == versions.size() - 1;
            Path dir = releasesDir.resolve(version.toString());
            String recordedTagName = readTagName(dir, version);
            if (!tags.contains(recordedTagName)) {
                if (newest) {
                    result.put(version, Status.PENDING);
                    continue;
                }
                throw fail("Release " + version + " has no tag " + recordedTagName + " in the repository, and it is"
                        + " not the newest snapshot, so it cannot be pending. " + FETCH_REMEDY);
            }
            proveTag(dir, version, recordedTagName, releasesPrefix, git);
            result.put(version, Status.PUBLISHED);
        }
        failOnOrphanTag(releasesDir, tags, tagName, versions);
        return new Verdict(result);
    }

    /** A tag matching the template for a version with no snapshot directory to match it. */
    private static void failOnOrphanTag(Path releasesDir, List<String> tags, TagName tagName,
                                        List<ReleaseVersion> versions) {
        for (String tag : tags) {
            Optional<ReleaseVersion> matched = tagName.match(tag);
            if (matched.isPresent() && !versions.contains(matched.get())) {
                throw fail("Tag " + tag + " matches agentic { release { tagName } }, but there is no " + releasesDir
                        + "/" + matched.get() + " directory to match it. " + FETCH_REMEDY + ", or remove the tag.");
            }
        }
    }

    /**
     * Proves {@code tagName}: it peels to a commit, that commit is an ancestor of {@code HEAD}, its
     * tree contains {@value #RELEASE_JSON} and {@value #IR_JSON} matching the manifest's own digest,
     * and both are byte-identical to the working copy's.
     */
    private static void proveTag(Path dir, ReleaseVersion version, String tagName, String releasesPrefix,
                                 GitQuery git) throws IOException {
        Optional<String> commit = git.peelToCommit(tagName);
        if (commit.isEmpty()) {
            throw fail("Tag " + tagName + " does not peel to a commit. " + FETCH_REMEDY);
        }
        if (!git.isAncestorOfHead(commit.get())) {
            throw fail("Tag " + tagName + "'s commit is not an ancestor of HEAD: release " + version + " was not"
                    + " published on this branch's history. " + FETCH_REMEDY);
        }
        String releaseJsonPath = releasesPrefix + version + "/" + RELEASE_JSON;
        String irPath = releasesPrefix + version + "/" + IR_JSON;
        byte[] taggedManifestBytes = git.blobBytes(commit.get(), releaseJsonPath);
        if (taggedManifestBytes.length == 0) {
            throw fail("Tag " + tagName + "'s commit has no " + releaseJsonPath + ". " + FETCH_REMEDY);
        }
        byte[] taggedIrBytes = git.blobBytes(commit.get(), irPath);
        if (taggedIrBytes.length == 0) {
            throw fail("Tag " + tagName + "'s commit has no " + irPath + ". " + FETCH_REMEDY);
        }
        ReleaseManifest taggedManifest;
        try {
            taggedManifest = ReleaseManifest.read(new String(taggedManifestBytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw fail("Tag " + tagName + "'s " + releaseJsonPath + " cannot be read: " + e.getMessage());
        }
        String expectedDigest = taggedManifest.digests().get(IR_JSON);
        String actualDigest = ContractRelease.sha256(taggedIrBytes);
        if (expectedDigest == null || !expectedDigest.equals(actualDigest)) {
            throw fail("Tag " + tagName + "'s " + irPath + " does not match the digest its own " + releaseJsonPath
                    + " records: the tagged commit's snapshot is internally inconsistent.");
        }
        byte[] workingManifestBytes = Files.readAllBytes(dir.resolve(RELEASE_JSON));
        byte[] workingIrBytes = Files.readAllBytes(dir.resolve(IR_JSON));
        if (!Arrays.equals(taggedManifestBytes, workingManifestBytes) || !Arrays.equals(taggedIrBytes,
                workingIrBytes)) {
            throw fail("Tag " + tagName + "'s snapshot of release " + version + " differs from the working copy's "
                    + dir + ": it was changed after it was tagged. Released contracts are immutable: restore it"
                    + " from version control.");
        }
    }

    private static String readTagName(Path dir, ReleaseVersion version) throws IOException {
        Path manifestFile = dir.resolve(RELEASE_JSON);
        if (!Files.isRegularFile(manifestFile)) {
            throw fail("Release " + version + " has no " + manifestFile + " to read its tag name from.");
        }
        try {
            return ReleaseManifest.read(Files.readString(manifestFile, StandardCharsets.UTF_8)).tagName();
        } catch (IllegalArgumentException e) {
            throw fail(manifestFile + " cannot be read: " + e.getMessage());
        }
    }

    /** The release directories under {@code releasesDir}, oldest version first. */
    private static List<ReleaseVersion> existingVersions(Path releasesDir) throws IOException {
        if (!Files.isDirectory(releasesDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(releasesDir)) {
            List<ReleaseVersion> versions = new ArrayList<>();
            entries.filter(Files::isDirectory).map(dir -> dir.getFileName().toString())
                    .filter(ReleaseVersion::matches).map(ReleaseVersion::parse).forEach(versions::add);
            versions.sort(Comparator.naturalOrder());
            return versions;
        }
    }

    private static GradleException fail(String message) {
        return new GradleException(PREFIX + message);
    }
}
