/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.demo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo's committed contract baseline is byte-identical to the IR its build emits, so the
 * demo's contract cannot change without its baseline being updated in the same change (FR-018).
 */
class ContractBaselineTest {

    private static final String IR_RESOURCE = "/META-INF/ai-atlas/api.ir.json";
    private static final String BASELINE_PROPERTY = "ai.atlas.demo.contractBaseline";

    @Test
    void committedBaselineMatchesEmittedIr() throws IOException {
        String baselinePath = System.getProperty(BASELINE_PROPERTY);
        assertThat(baselinePath).as("system property " + BASELINE_PROPERTY).isNotBlank();
        Path baseline = Path.of(baselinePath);
        assertThat(baseline).as("committed baseline").isRegularFile();

        byte[] emitted;
        try (InputStream in = ContractBaselineTest.class.getResourceAsStream(IR_RESOURCE)) {
            assertThat(in).as(IR_RESOURCE + " on the classpath").isNotNull();
            emitted = in.readAllBytes();
        }

        assertThat(Files.readAllBytes(baseline))
                .as("demo/.atlas/api.ir.json differs from the emitted IR — run the processor and"
                        + " commit the new baseline with the contract change")
                .isEqualTo(emitted);
    }
}
