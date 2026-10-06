/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.ContractResources;
import com.egoge.ai.atlas.processor.contract.EffectiveOptions;
import com.egoge.ai.atlas.processor.generator.McpToolsResourceGenerator;
import com.egoge.ai.atlas.processor.generator.OpenApiGenerator;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ClassOutputResources}: the release-side validation of a class output against its {@link
 * ContractResources.Manifest}, read-only in every case (D2.6, D2.8, AC2, AC4).
 */
class ClassOutputResourcesTest {

    // "empty" fixtures need only api.ir.json listed (required() adds nothing else for an empty
    // contract with constraints off); "declared" fixtures with constraints on also require the
    // major's OpenAPI document and mcp-tools.json, matching ContractResources.required.
    private static final EffectiveOptions CONFIG_EMPTY = new EffectiveOptions("/api", 1, "1.0.0", false, false, false, false);
    private static final EffectiveOptions CONFIG_DECLARED = new EffectiveOptions("/api", 1, "1.0.0", true, false, false, false);
    private static final byte[] IR_BYTES = "{\"irVersion\": 1}\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OPENAPI_BYTES = "{\"openapi\": \"3.0.3\"}\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MCP_TOOLS_BYTES = "{\"tools\": []}\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path classOutput;

    @Test
    void aMissingListedArtifactFailsNamingThePath() throws IOException {
        writeManifest(emptyManifest(Map.of(ContractIr.RESOURCE_PATH, ReleaseSnapshots.sha256(IR_BYTES))));
        // IR_BYTES is listed in the manifest, but never written to disk.

        assertThatThrownBy(() -> ClassOutputResources.validate(List.of(classOutput.toFile())))
                .isInstanceOf(GradleException.class).hasMessageContaining(ContractIr.RESOURCE_PATH)
                .hasMessageContaining("missing from");
    }

    @Test
    void aDigestMismatchFailsNamingThePath() throws IOException {
        write(ContractIr.RESOURCE_PATH, IR_BYTES);
        writeManifest(emptyManifest(Map.of(ContractIr.RESOURCE_PATH,
                ReleaseSnapshots.sha256("{\"irVersion\": 2}\n".getBytes(StandardCharsets.UTF_8)))));

        assertThatThrownBy(() -> ClassOutputResources.validate(List.of(classOutput.toFile())))
                .isInstanceOf(GradleException.class).hasMessageContaining(ContractIr.RESOURCE_PATH)
                .hasMessageContaining("does not match the digest");
    }

    @Test
    void anUnlistedReservedFileFailsNamingThePathAndMentioningClean() throws IOException {
        write(ContractIr.RESOURCE_PATH, IR_BYTES);
        write(McpToolsResourceGenerator.RESOURCE_PATH, MCP_TOOLS_BYTES); // present, but not listed below
        writeManifest(emptyManifest(Map.of(ContractIr.RESOURCE_PATH, ReleaseSnapshots.sha256(IR_BYTES))));

        assertThatThrownBy(() -> ClassOutputResources.validate(List.of(classOutput.toFile())))
                .isInstanceOf(GradleException.class).hasMessageContaining(McpToolsResourceGenerator.RESOURCE_PATH)
                .hasMessageContaining("clean");
    }

    @Test
    void aMissingRequiredArtifactFailsAsAProcessorBug() throws IOException {
        // A "declared" manifest that lists no artifacts at all: api.ir.json is always required.
        writeManifest(declaredManifest(Map.of()));

        assertThatThrownBy(() -> ClassOutputResources.validate(List.of(classOutput.toFile())))
                .isInstanceOf(GradleException.class).hasMessageContaining(ContractIr.RESOURCE_PATH)
                .hasMessageContaining("processor bug");
    }

    @Test
    void anUnrelatedMetaInfFileIsIgnored() throws IOException {
        write(ContractIr.RESOURCE_PATH, IR_BYTES);
        write("META-INF/other/x.json", "{}\n".getBytes(StandardCharsets.UTF_8));
        writeManifest(emptyManifest(Map.of(ContractIr.RESOURCE_PATH, ReleaseSnapshots.sha256(IR_BYTES))));

        ClassOutputResources.Result result = ClassOutputResources.validate(List.of(classOutput.toFile()));

        assertThat(result.artifacts()).containsOnlyKeys("api.ir.json");
    }

    @Test
    void aFullyValidClassOutputSucceedsAndReturnsExactlySnapshottedArtifacts() throws IOException {
        String openApiPath = OpenApiGenerator.RESOURCE_DIR + "openapi-v1.json";
        write(ContractIr.RESOURCE_PATH, IR_BYTES);
        write(openApiPath, OPENAPI_BYTES);
        write(McpToolsResourceGenerator.RESOURCE_PATH, MCP_TOOLS_BYTES);
        String json = writeManifest(declaredManifest(Map.of(
                ContractIr.RESOURCE_PATH, ReleaseSnapshots.sha256(IR_BYTES),
                openApiPath, ReleaseSnapshots.sha256(OPENAPI_BYTES),
                McpToolsResourceGenerator.RESOURCE_PATH, ReleaseSnapshots.sha256(MCP_TOOLS_BYTES))));

        ClassOutputResources.Result result = ClassOutputResources.validate(List.of(classOutput.toFile()));

        assertThat(result.contractResourcesJson()).isEqualTo(json);
        assertThat(result.artifacts()).containsOnlyKeys("api.ir.json", "openapi-v1.json", "mcp-tools.json");
        assertThat(result.artifacts().get("api.ir.json")).isEqualTo(IR_BYTES);
        assertThat(result.artifacts().get("openapi-v1.json")).isEqualTo(OPENAPI_BYTES);
        assertThat(result.artifacts().get("mcp-tools.json")).isEqualTo(MCP_TOOLS_BYTES);
    }

    @Test
    void itNeverWritesDeletesOrModifiesTheClassOutputWhetherItThrowsOrSucceeds() throws IOException {
        write(ContractIr.RESOURCE_PATH, IR_BYTES);
        writeManifest(emptyManifest(Map.of(ContractIr.RESOURCE_PATH, ReleaseSnapshots.sha256(IR_BYTES))));
        Map<String, byte[]> before = snapshot();

        ClassOutputResources.validate(List.of(classOutput.toFile()));

        assertSameContents(before, snapshot());

        write(McpToolsResourceGenerator.RESOURCE_PATH, MCP_TOOLS_BYTES); // now an unlisted reserved file: fails
        Map<String, byte[]> beforeFailure = snapshot();

        assertThatThrownBy(() -> ClassOutputResources.validate(List.of(classOutput.toFile())))
                .isInstanceOf(GradleException.class);

        assertSameContents(beforeFailure, snapshot());
    }

    /** {@code byte[]} values defeat {@link Map#equals}: this compares keys and bytes explicitly. */
    private static void assertSameContents(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertThat(actual.keySet()).isEqualTo(expected.keySet());
        expected.forEach((path, bytes) -> assertThat(actual.get(path)).as(path).isEqualTo(bytes));
    }

    // ------------------------------------------------------------ helpers

    private static ContractResources.Manifest emptyManifest(Map<String, String> artifacts) {
        return new ContractResources.Manifest("empty", CONFIG_EMPTY, new TreeMap<>(artifacts));
    }

    private static ContractResources.Manifest declaredManifest(Map<String, String> artifacts) {
        return new ContractResources.Manifest("declared", CONFIG_DECLARED, new TreeMap<>(artifacts));
    }

    private String writeManifest(ContractResources.Manifest manifest) throws IOException {
        String json = manifest.write();
        write(ContractResources.MANIFEST_PATH, json.getBytes(StandardCharsets.UTF_8));
        return json;
    }

    private void write(String relativePath, byte[] bytes) throws IOException {
        Path file = classOutput.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    /** Every regular file under {@link #classOutput}, mapped to its bytes. */
    private Map<String, byte[]> snapshot() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(classOutput)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                files.put(classOutput.relativize(path).toString().replace(File.separatorChar, '/'),
                        Files.readAllBytes(path));
            }
        }
        return files;
    }
}
