/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.release.ReleaseVersion;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReleaseSnapshots} and {@link ReleaseSnapshotHistory}'s tests' contract: {@code test.Order},
 * with an {@code id} and any other fields, and {@code test.OrderService#find(Long)} returning it,
 * compiled by the processor at a major (F1, moved from the processor's own
 * {@code ReleaseFixtures}).
 */
final class ReleaseFixtures {

    /** A field {@code legacy} with the given extra {@code @AgenticField} attributes, and its getter. */
    static final String LEGACY = """
            @AgenticField(description = "Legacy code"%s) private String legacy;
            public String getLegacy() { return legacy; }
            """;
    /** A field {@code note}, and its getter. */
    static final String NOTE = """
            @AgenticField(description = "A note") private String note;
            public String getNote() { return note; }
            """;
    /** A minimal, valid contract-resources manifest, for tests that do not exercise it directly. */
    static final String CONTRACT_RESOURCES_JSON = "{\"contract\": \"declared\"}\n";
    /** A resolved tag name, for tests that do not exercise F1's tag-name resolution directly. */
    static final String TAG_NAME = "v0.0.0-test";

    private static final String ORDER = """
            package test;
            import com.egoge.ai.atlas.annotations.*;
            @AgenticEntity(description = "An order")
            public class Order {
                @AgenticField(description = "Id") private Long id;
                public Long getId() { return id; }
                %s
            }
            """;
    private static final String SERVICE = """
            package test;
            import com.egoge.ai.atlas.annotations.*;
            @AgenticExposed(description = "Orders", returnType = Order.class)
            public class OrderService {
                @AgenticExposed(description = "Finds an order by id")
                public Order find(Long id) { return null; }
            }
            """;

    private ReleaseFixtures() {
    }

    /** The IR text of {@code Order} with {@code members} and {@code OrderService}, at {@code major}. */
    static String irJson(int major, String members) {
        List<JavaFileObject> sources = List.of(JavaFileObjects.forSourceString("test.Order", ORDER.formatted(members)),
                JavaFileObjects.forSourceString("test.OrderService", SERVICE));
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions("-A" + AgenticProcessor.OPT_API_MAJOR + "=" + major).compile(sources);
        assertThat(compilation.status()).as(compilation.diagnostics().toString())
                .isEqualTo(Compilation.Status.SUCCESS);
        JavaFileObject ir = compilation.generatedFile(StandardLocation.CLASS_OUTPUT, ContractIr.RESOURCE_PATH)
                .orElseThrow();
        try (var in = ir.openInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A {@link ReleaseSnapshots.Request#artifacts()} map for {@code ReleaseSnapshots.release}: the
     * OpenAPI document of {@code ir}'s {@code apiMajor}, and/or the MCP tool specifications, each
     * only when its text is not {@code null}.
     */
    static Map<String, byte[]> artifacts(String ir, String openApi, String mcpTools) {
        Map<String, byte[]> artifacts = new LinkedHashMap<>();
        // As the plugin passes them: the class output's IR is among the artifacts
        artifacts.put(ReleaseSnapshots.IR_FILE, ir.getBytes(StandardCharsets.UTF_8));
        if (openApi != null) {
            artifacts.put(ReleaseSnapshots.openApiFile(apiMajor(ir)), openApi.getBytes(StandardCharsets.UTF_8));
        }
        if (mcpTools != null) {
            artifacts.put(ReleaseSnapshots.MCP_TOOLS_FILE, mcpTools.getBytes(StandardCharsets.UTF_8));
        }
        return artifacts;
    }

    /**
     * Every release already on disk under {@code releases}, F1's {@code published} set as most
     * tests want it: everything already there counts as published, so a test not exercising F1's
     * pending rule directly never has to think about it.
     */
    static Set<ReleaseVersion> allExistingVersions(Path releases) throws IOException {
        Set<ReleaseVersion> versions = new LinkedHashSet<>();
        if (!Files.isDirectory(releases)) {
            return versions;
        }
        try (Stream<Path> entries = Files.list(releases)) {
            entries.filter(Files::isDirectory).map(dir -> dir.getFileName().toString())
                    .filter(ReleaseVersion::matches).map(ReleaseVersion::parse).forEach(versions::add);
        }
        return versions;
    }

    /** {@code ir}'s {@code apiMajor}, read without a full {@code ContractIr} parse. */
    private static int apiMajor(String ir) {
        try {
            return com.egoge.ai.atlas.processor.contract.IrJson.parse(ir, "test").apiMajor();
        } catch (com.egoge.ai.atlas.processor.contract.IrJson.IrReadException e) {
            throw new IllegalStateException(e);
        }
    }
}
