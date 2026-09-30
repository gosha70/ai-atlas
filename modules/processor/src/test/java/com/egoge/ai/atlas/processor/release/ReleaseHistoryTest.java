/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.release;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.egoge.ai.atlas.processor.release.ReleaseFixtures.irJson;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ReleaseHistory}: verified reads of the releases {@link ContractRelease} writes. */
class ReleaseHistoryTest {

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
        ContractRelease.release(releases, changelog, request("1.0.0", accept(irJson(1, "")), null, null));
        Path ir = releases.resolve("1.0.0/api.ir.json");
        String original = Files.readString(ir);

        Files.writeString(ir, original.replace("\"Id\"", "\"Identifier\""));
        assertThatThrownBy(() -> ReleaseHistory.history(releases))
                .hasMessageContaining("Released file " + ir + " was modified after release")
                .hasMessageContaining("restore it from version control");
        assertThatThrownBy(() -> ContractRelease.release(releases, changelog,
                request("1.1.0", accept(irJson(1, "")), null, null)))
                .hasMessageContaining("was modified after release");

        Files.delete(ir);
        assertThatThrownBy(() -> ReleaseHistory.history(releases)).hasMessageContaining("was deleted after release");

        Files.writeString(ir, original);
        Files.writeString(releases.resolve("1.0.0/notes.txt"), "x");
        assertThatThrownBy(() -> ReleaseHistory.history(releases))
                .hasMessageContaining("holds [notes.txt], which release.json does not record");
    }

    @Test
    void aManifestNamingAnotherVersionFailsVerification() throws Exception {
        ContractRelease.release(releases, changelog, request("1.0.0", accept(irJson(1, "")), null, null));
        Files.move(releases.resolve("1.0.0"), releases.resolve("1.0.1"));

        assertThatThrownBy(() -> ReleaseHistory.history(releases))
                .hasMessageContaining("records version 1.0.0, not 1.0.1");
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

    private ContractRelease.Request request(String version, String ir, String openApi, String mcpTools) {
        return new ContractRelease.Request(version, false, ReleasePolicy.Policy.DEFAULT, baseline,
                ir.getBytes(StandardCharsets.UTF_8), bytes(openApi), bytes(mcpTools));
    }

    private static byte[] bytes(String text) {
        return text == null ? null : text.getBytes(StandardCharsets.UTF_8);
    }
}
