/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.release.ReleasePolicy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.egoge.ai.atlas.plugin.ReleaseFixtures.CONTRACT_RESOURCES_JSON;
import static com.egoge.ai.atlas.plugin.ReleaseFixtures.TAG_NAME;
import static com.egoge.ai.atlas.plugin.ReleaseFixtures.artifacts;
import static com.egoge.ai.atlas.plugin.ReleaseFixtures.irJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ReleaseSnapshotHistory}: verified reads of the releases {@link ReleaseSnapshots} writes. */
class ReleaseSnapshotHistoryTest {

    @TempDir
    Path dir;
    Path baseline;
    Path releases;
    Path changelog;

    @BeforeEach
    void paths() {
        baseline = dir.resolve(".atlas/api.ir.json");
        releases = dir.resolve(".atlas/releases");
        changelog = dir.resolve(".atlas/CHANGELOG.md");
    }

    @Test
    void aReleasedFileEditedDeletedOrAddedFailsVerification() throws Exception {
        ReleaseSnapshots.release(releases, changelog, request("1.0.0", accept(irJson(1, "")), null, null));
        Path ir = releases.resolve("1.0.0/api.ir.json");
        String original = Files.readString(ir);

        Files.writeString(ir, original.replace("\"Id\"", "\"Identifier\""));
        assertThatThrownBy(() -> ReleaseSnapshotHistory.history(releases))
                .hasMessageContaining("Released file " + ir + " was modified after release")
                .hasMessageContaining("restore it from version control");
        assertThatThrownBy(() -> ReleaseSnapshots.release(releases, changelog,
                request("1.1.0", accept(irJson(1, "")), null, null)))
                .hasMessageContaining("was modified after release");

        Files.delete(ir);
        assertThatThrownBy(() -> ReleaseSnapshotHistory.history(releases)).hasMessageContaining("was deleted after release");

        Files.writeString(ir, original);
        Files.writeString(releases.resolve("1.0.0/notes.txt"), "x");
        assertThatThrownBy(() -> ReleaseSnapshotHistory.history(releases))
                .hasMessageContaining("holds [notes.txt], which release.json does not record");
    }

    @Test
    void aManifestNamingAnotherVersionFailsVerification() throws Exception {
        ReleaseSnapshots.release(releases, changelog, request("1.0.0", accept(irJson(1, "")), null, null));
        Files.move(releases.resolve("1.0.0"), releases.resolve("1.0.1"));

        assertThatThrownBy(() -> ReleaseSnapshotHistory.history(releases))
                .hasMessageContaining("records version 1.0.0, not 1.0.1");
    }

    @Test
    void checkConsistencyPassesForAConsistentHistory() throws Exception {
        ReleaseSnapshots.release(releases, changelog, request("1.0.0", accept(irJson(1, "")), null, null));
        ReleaseSnapshots.release(releases, changelog, request("1.1.0", accept(irJson(1, ReleaseFixtures.NOTE)), null,
                null));

        assertThat(ReleaseSnapshotHistory.checkConsistency(releases, changelog)).isEqualTo(2);
    }

    @Test
    void checkConsistencySucceedsSilentlyWithNoReleasesAndNoChangelog() throws Exception {
        assertThat(ReleaseSnapshotHistory.checkConsistency(releases, changelog)).isZero();
    }

    @Test
    void aBrokenPreviousChainFailsConsistency() throws Exception {
        ReleaseSnapshots.release(releases, changelog, request("1.0.0", accept(irJson(1, "")), null, null));
        ReleaseSnapshots.release(releases, changelog, request("1.1.0", accept(irJson(1, ReleaseFixtures.NOTE)), null,
                null));
        Path manifestFile = releases.resolve("1.1.0/release.json");
        Files.writeString(manifestFile, Files.readString(manifestFile).replace("\"1.0.0\"", "\"0.9.0\""));

        assertThatThrownBy(() -> ReleaseSnapshotHistory.checkConsistency(releases, changelog))
                .hasMessageContaining("records previous 0.9.0")
                .hasMessageContaining("preceding release in " + releases + " is 1.0.0");
    }

    @Test
    void anEditedChangelogFailsConsistency() throws Exception {
        ReleaseSnapshots.release(releases, changelog, request("1.0.0", accept(irJson(1, "")), null, null));

        Files.writeString(changelog, Files.readString(changelog) + "\nHand-edited.\n");

        assertThatThrownBy(() -> ReleaseSnapshotHistory.checkConsistency(releases, changelog))
                .hasMessageContaining("does not equal what the release history")
                .hasMessageContaining("edited after release");
    }

    // ------------------------------------------------------------ helpers

    /** Writes {@code ir} as the baseline and returns it. */
    private String accept(String ir) {
        try {
            Files.createDirectories(baseline.getParent());
            Files.writeString(baseline, ir, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return ir;
    }

    private ReleaseSnapshots.Request request(String version, String ir, String openApi, String mcpTools)
            throws IOException {
        return new ReleaseSnapshots.Request(version, false, ReleasePolicy.Policy.DEFAULT, baseline,
                ir.getBytes(StandardCharsets.UTF_8), artifacts(ir, openApi, mcpTools), CONTRACT_RESOURCES_JSON,
                TAG_NAME, ReleaseFixtures.allExistingVersions(releases));
    }
}
