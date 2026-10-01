/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.release.ReleaseManifest;
import com.egoge.ai.atlas.processor.release.ReleasePolicy;
import com.egoge.ai.atlas.processor.release.ReleaseVersion;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PublishedHistory}: F1's D10.5 table, tag proof and the pending rule, against a fake
 * {@link GitQuery} (never a real repository: that is {@link GitRepositoryTest}'s job).
 */
class PublishedHistoryTest {

    private static final String IR = "{\"irVersion\": 1, \"apiMajor\": 1}";
    private static final TagName TAG_NAME = TagName.parse(TagName.DEFAULT_TEMPLATE);

    @TempDir
    Path releases;

    @Test
    void aGenuineFirstReleaseNeedsNoGitHistory() throws IOException {
        FakeGit git = new FakeGit().noRepository();

        PublishedHistory.Verdict verdict = PublishedHistory.verify(releases.resolve("does-not-exist"), "",
                TAG_NAME, git);

        assertThat(verdict.statuses()).isEmpty();
    }

    @Test
    void aShallowRepositoryFailsEvenWithNoSnapshots() {
        FakeGit git = new FakeGit().shallow();

        assertThatThrownBy(() -> PublishedHistory.verify(releases.resolve("does-not-exist"), "", TAG_NAME, git))
                .isInstanceOf(GradleException.class).hasMessageContaining("shallow clone");
    }

    @Test
    void snapshotsWithoutARepositoryFail() throws IOException {
        write("1.0.0", "v1.0.0");
        FakeGit git = new FakeGit().noRepository();

        assertThatThrownBy(() -> PublishedHistory.verify(releases, "", TAG_NAME, git))
                .isInstanceOf(GradleException.class).hasMessageContaining("no git repository was found");
    }

    @Test
    void aMatchingTagWithNoSnapshotDirectoryFails() {
        FakeGit git = new FakeGit().withTag("v1.0.0", "c1");

        assertThatThrownBy(() -> PublishedHistory.verify(releases.resolve("does-not-exist"), "", TAG_NAME, git))
                .isInstanceOf(GradleException.class).hasMessageContaining("Tag v1.0.0")
                .hasMessageContaining("snapshot for it")
                // A project tagged before ai-atlas needs its own pattern, not its published tags deleted
                .hasMessageContaining("tagName.set(\"api-v{version}\")")
                .hasMessageNotContaining("remove the tag").hasMessageNotContaining(".,");
    }

    @Test
    void anOlderSnapshotWithoutItsTagFails() throws IOException {
        write("1.0.0", "v1.0.0");
        write("1.1.0", "v1.1.0");
        FakeGit git = new FakeGit(); // no tags at all

        assertThatThrownBy(() -> PublishedHistory.verify(releases, "", TAG_NAME, git))
                .isInstanceOf(GradleException.class).hasMessageContaining("Release 1.0.0 has no tag v1.0.0");
    }

    @Test
    void theNewestSnapshotWithoutATagIsPendingNotAFailure() throws IOException {
        write("1.0.0", "v1.0.0");
        write("1.1.0", "v1.1.0");
        FakeGit git = new FakeGit().withTag("v1.0.0", "c1");
        git.blob("c1", "1.0.0/release.json", Files.readAllBytes(releases.resolve("1.0.0/release.json")));
        git.blob("c1", "1.0.0/api.ir.json", Files.readAllBytes(releases.resolve("1.0.0/api.ir.json")));

        PublishedHistory.Verdict verdict = PublishedHistory.verify(releases, "", TAG_NAME, git);

        assertThat(verdict.statuses().get(ReleaseVersion.parse("1.0.0"))).isEqualTo(PublishedHistory.Status.PUBLISHED);
        assertThat(verdict.statuses().get(ReleaseVersion.parse("1.1.0"))).isEqualTo(PublishedHistory.Status.PENDING);
        assertThat(verdict.published()).containsExactly(ReleaseVersion.parse("1.0.0"));
    }

    @Test
    void aTagThatDoesNotPeelToACommitFails() throws IOException {
        write("1.0.0", "v1.0.0");
        FakeGit git = new FakeGit().withTagNotPeeling("v1.0.0");

        assertThatThrownBy(() -> PublishedHistory.verify(releases, "", TAG_NAME, git))
                .isInstanceOf(GradleException.class).hasMessageContaining("does not peel to a commit");
    }

    @Test
    void aTagWhoseCommitIsNotAnAncestorOfHeadFails() throws IOException {
        write("1.0.0", "v1.0.0");
        FakeGit git = new FakeGit().withTag("v1.0.0", "c1").notAncestor("c1");

        assertThatThrownBy(() -> PublishedHistory.verify(releases, "", TAG_NAME, git))
                .isInstanceOf(GradleException.class).hasMessageContaining("not an ancestor of HEAD");
    }

    @Test
    void aTreeMissingReleaseJsonFails() throws IOException {
        write("1.0.0", "v1.0.0");
        FakeGit git = new FakeGit().withTag("v1.0.0", "c1"); // no blobs recorded for c1

        assertThatThrownBy(() -> PublishedHistory.verify(releases, "", TAG_NAME, git))
                .isInstanceOf(GradleException.class).hasMessageContaining("has no").hasMessageContaining(
                        "release.json");
    }

    @Test
    void aTreeMissingApiIrJsonFails() throws IOException {
        write("1.0.0", "v1.0.0");
        FakeGit git = new FakeGit().withTag("v1.0.0", "c1");
        git.blob("c1", "1.0.0/release.json", Files.readAllBytes(releases.resolve("1.0.0/release.json")));

        assertThatThrownBy(() -> PublishedHistory.verify(releases, "", TAG_NAME, git))
                .isInstanceOf(GradleException.class).hasMessageContaining("has no").hasMessageContaining(
                        "api.ir.json");
    }

    @Test
    void aDigestMismatchInsideTheTagFails() throws IOException {
        write("1.0.0", "v1.0.0");
        FakeGit git = new FakeGit().withTag("v1.0.0", "c1");
        git.blob("c1", "1.0.0/release.json", Files.readAllBytes(releases.resolve("1.0.0/release.json")));
        git.blob("c1", "1.0.0/api.ir.json", "{\"irVersion\": 1, \"apiMajor\": 1, \"different\": true}"
                .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> PublishedHistory.verify(releases, "", TAG_NAME, git))
                .isInstanceOf(GradleException.class).hasMessageContaining("does not match the digest");
    }

    @Test
    void aTagThatDiffersFromTheWorkingCopyFails() throws IOException {
        write("1.0.0", "v1.0.0");
        byte[] taggedIr = Files.readAllBytes(releases.resolve("1.0.0/api.ir.json"));
        byte[] taggedManifest = Files.readAllBytes(releases.resolve("1.0.0/release.json"));
        // Mutate the working copy after it was (supposedly) tagged.
        Files.writeString(releases.resolve("1.0.0/api.ir.json"), IR + " ");
        FakeGit git = new FakeGit().withTag("v1.0.0", "c1");
        git.blob("c1", "1.0.0/release.json", taggedManifest);
        git.blob("c1", "1.0.0/api.ir.json", taggedIr);

        assertThatThrownBy(() -> PublishedHistory.verify(releases, "", TAG_NAME, git))
                .isInstanceOf(GradleException.class).hasMessageContaining("differs from the working copy");
    }

    @Test
    void everyTagProvedPublishesEveryRelease() throws IOException {
        write("1.0.0", "v1.0.0");
        write("1.1.0", "v1.1.0");
        FakeGit git = new FakeGit().withTag("v1.0.0", "c1").withTag("v1.1.0", "c2");
        git.blob("c1", "1.0.0/release.json", Files.readAllBytes(releases.resolve("1.0.0/release.json")));
        git.blob("c1", "1.0.0/api.ir.json", Files.readAllBytes(releases.resolve("1.0.0/api.ir.json")));
        git.blob("c2", "1.1.0/release.json", Files.readAllBytes(releases.resolve("1.1.0/release.json")));
        git.blob("c2", "1.1.0/api.ir.json", Files.readAllBytes(releases.resolve("1.1.0/api.ir.json")));

        PublishedHistory.Verdict verdict = PublishedHistory.verify(releases, "", TAG_NAME, git);

        assertThat(verdict.published()).containsExactlyInAnyOrder(ReleaseVersion.parse("1.0.0"),
                ReleaseVersion.parse("1.1.0"));
    }

    @Test
    void anUnrelatedTagNameNeverStandsInForTheConvention() throws IOException {
        write("1.0.0", "v1.0.0");
        write("1.1.0", "v1.1.0");
        // "1.0.0" (no "v") never matches the default template, so release 1.0.0 (not the newest) is
        // simply treated as untagged, and fails rather than being merely pending.
        FakeGit git = new FakeGit().withTag("1.0.0", "c1");

        assertThatThrownBy(() -> PublishedHistory.verify(releases, "", TAG_NAME, git))
                .isInstanceOf(GradleException.class).hasMessageContaining("Release 1.0.0 has no tag v1.0.0");
    }

    // ------------------------------------------------------------ helpers

    /** Writes a minimal committed snapshot at {@code version}, recording {@code tagName}. */
    private void write(String version, String tagName) throws IOException {
        Path dir = releases.resolve(version);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("api.ir.json"), IR, StandardCharsets.UTF_8);
        byte[] irBytes = IR.getBytes(StandardCharsets.UTF_8);
        ReleaseManifest manifest = new ReleaseManifest(version, 1, 1, null, tagName, ReleasePolicy.Policy.DEFAULT,
                Map.of("api.ir.json", ReleaseSnapshots.sha256(irBytes)), Map.of("contract", "empty"));
        Files.writeString(dir.resolve("release.json"), manifest.write(), StandardCharsets.UTF_8);
    }

    /** A fake {@link GitQuery}: canned answers, no process, no filesystem beyond what is given. */
    private static final class FakeGit implements GitQuery {
        private boolean insideWorkTree = true;
        private boolean shallow;
        private final Map<String, String> tagCommits = new LinkedHashMap<>();
        private final Map<String, byte[]> blobs = new LinkedHashMap<>();
        private final List<String> nonAncestors = new ArrayList<>();

        FakeGit noRepository() {
            insideWorkTree = false;
            return this;
        }

        FakeGit shallow() {
            this.shallow = true;
            return this;
        }

        FakeGit withTag(String tag, String commit) {
            tagCommits.put(tag, commit);
            return this;
        }

        FakeGit withTagNotPeeling(String tag) {
            tagCommits.put(tag, null);
            return this;
        }

        FakeGit notAncestor(String commit) {
            nonAncestors.add(commit);
            return this;
        }

        void blob(String commit, String path, byte[] bytes) {
            blobs.put(commit + ":" + path, bytes);
        }

        @Override
        public boolean isInsideWorkTree() {
            return insideWorkTree;
        }

        @Override
        public boolean isShallow() {
            return shallow;
        }

        @Override
        public List<String> tags() {
            return List.copyOf(tagCommits.keySet());
        }

        @Override
        public Optional<String> peelToCommit(String tag) {
            return Optional.ofNullable(tagCommits.get(tag));
        }

        @Override
        public boolean isAncestorOfHead(String commit) {
            return !nonAncestors.contains(commit);
        }

        @Override
        public byte[] blobBytes(String commit, String path) {
            return blobs.getOrDefault(commit + ":" + path, new byte[0]);
        }

        @Override
        public Optional<String> headCommit() {
            return Optional.ofNullable(head);
        }

        FakeGit withHead(String commit) {
            this.head = commit;
            return this;
        }

        private String head;
    }
}
