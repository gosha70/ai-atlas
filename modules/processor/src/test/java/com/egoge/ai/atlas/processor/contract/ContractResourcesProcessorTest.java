/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.egoge.ai.atlas.processor.contract.ContractResources.Manifest;
import com.egoge.ai.atlas.processor.generator.McpToolsResourceGenerator;
import com.egoge.ai.atlas.processor.generator.OpenApiGenerator;
import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import static com.egoge.ai.atlas.processor.contract.GateFixtures.BASELINE;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.MAJOR;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.assertPasses;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.fixture;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.writeBaseline;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The processor writes {@link ContractResources#MANIFEST_PATH} in the final round (D2.4, D2.5,
 * AC3): every digest matches the artifact it names, the effective configuration follows the
 * compilation's options (last {@code -A} value wins), and the manifest never lists itself.
 */
class ContractResourcesProcessorTest {

    private static final String CONSTRAINTS = "-A" + AgenticProcessor.OPT_CONSTRAINTS + "=true";

    @TempDir
    Path dir;

    @Test
    void everyDigestEqualsTheShaOfTheArtifactItNames() {
        Compilation compilation = compile(fixture().sources(), MAJOR + "1");

        Manifest manifest = manifestOf(compilation);

        assertThat(manifest.artifacts()).isNotEmpty();
        manifest.artifacts().forEach((path, digest) -> assertThat(digest).as(path)
                .isEqualTo(sha256(generatedBytes(compilation, path))));
    }

    @Test
    void theIrIsAlwaysListed() {
        Compilation compilation = compile(fixture().sources(), MAJOR + "1");

        Manifest manifest = manifestOf(compilation);

        assertThat(manifest.artifacts()).containsKey(ContractIr.RESOURCE_PATH);
    }

    @Test
    void constraintsOnListsMcpTools() {
        Compilation compilation = compile(fixture().sources(), MAJOR + "1", CONSTRAINTS);

        Manifest manifest = manifestOf(compilation);

        assertThat(manifest.configuration().constraints()).isTrue();
        assertThat(manifest.artifacts()).containsKey(McpToolsResourceGenerator.RESOURCE_PATH);
    }

    @Test
    void theRestOptionIsRecorded() {
        assertThat(manifestOf(compile(fixture().sources(), MAJOR + "1")).configuration().rest()).isFalse();
        assertThat(manifestOf(compile(fixture().sources(), MAJOR + "1", "-Aai.atlas.rest=true"))
                .configuration().rest()).isTrue();
    }

    @Test
    void majorTwoListsTheVersionedDocumentAndTheAlias() {
        Compilation compilation = compile(fixture().sources(), MAJOR + "2");

        Manifest manifest = manifestOf(compilation);

        assertThat(manifest.artifacts()).containsKeys(
                "META-INF/openapi/openapi-v2.json", OpenApiGenerator.RESOURCE_DIR + "openapi.json");
    }

    @Test
    void aConfiguredBaselineListsTheContractDiff() throws IOException {
        Path baseline = writeBaseline(dir);

        Compilation compilation = compile(fixture().sources(), MAJOR + "2", BASELINE + baseline);

        Manifest manifest = manifestOf(compilation);
        assertThat(manifest.artifacts()).containsKey(ContractGate.DIFF_RESOURCE_PATH);
    }

    @Test
    void duplicateMajorArgumentsRecordTheLastValue() {
        Compilation compilation = compile(fixture().sources(), MAJOR + "1", MAJOR + "2");

        Manifest manifest = manifestOf(compilation);

        assertThat(manifest.configuration().apiMajor()).isEqualTo(2);
        assertThat(manifest.artifacts()).containsKey("META-INF/openapi/openapi-v2.json")
                .doesNotContainKey("META-INF/openapi/openapi-v1.json");
    }

    @Test
    void theManifestNeverListsItself() {
        Compilation compilation = compile(fixture().sources(), MAJOR + "1");

        Manifest manifest = manifestOf(compilation);

        assertThat(manifest.artifacts()).doesNotContainKey(ContractResources.MANIFEST_PATH);
    }

    private static Compilation compile(java.util.List<JavaFileObject> sources, String... options) {
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions((Object[]) options).compile(sources);
        assertPasses(compilation);
        return compilation;
    }

    private static Manifest manifestOf(Compilation compilation) {
        return Manifest.read(readText(compilation, ContractResources.MANIFEST_PATH));
    }

    private static String readText(Compilation compilation, String path) {
        return new String(generatedBytes(compilation, path), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] generatedBytes(Compilation compilation, String path) {
        Optional<JavaFileObject> file = compilation.generatedFile(StandardLocation.CLASS_OUTPUT, "", path);
        assertThat(file).as("generated " + path).isPresent();
        try (var in = file.get().openInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
