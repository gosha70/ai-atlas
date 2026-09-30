/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.release;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.IrJson;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The release tests' contract: {@code test.Order}, with an {@code id} and any other fields, and
 * {@code test.OrderService#find(Long)} returning it, compiled by the processor at a major.
 */
final class ReleaseFixtures {

    /** A field {@code legacy} with the given extra {@code @AgenticField} attributes, and its getter. */
    static final String LEGACY = """
            @AgenticField(description = "Legacy code"%s) private String legacy;
            public String getLegacy() { return legacy; }
            """;
    /** Turns per-channel field projections on. */
    static final String PROJECTIONS = "-A" + AgenticProcessor.OPT_PROJECTIONS + "=true";
    /** A field {@code note}, and its getter. */
    static final String NOTE = """
            @AgenticField(description = "A note") private String note;
            public String getNote() { return note; }
            """;
    /** A minimal, valid contract-resources manifest, for tests that do not exercise it directly. */
    static final String CONTRACT_RESOURCES_JSON = "{\"contract\": \"declared\"}\n";

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
                @AgenticExposed(description = "Finds an order by id"%s)
                public Order find(Long id) { return null; }
            }
            """;

    private ReleaseFixtures() {
    }

    /** The IR text of {@code Order} with {@code members} and {@code OrderService}, at {@code major}. */
    static String irJson(int major, String members) {
        return irJson(major, members, "");
    }

    /**
     * The IR text of {@code Order} with {@code members} and {@code OrderService}, whose
     * {@code find} carries {@code findAttributes}, at {@code major}.
     */
    static String irJson(int major, String members, String findAttributes, String... options) {
        return irJson(major, List.of(JavaFileObjects.forSourceString("test.Order", ORDER.formatted(members)),
                JavaFileObjects.forSourceString("test.OrderService", SERVICE.formatted(findAttributes))), options);
    }

    /** The IR text of {@code sources}, compiled at {@code major} with any other processor options. */
    static String irJson(int major, List<JavaFileObject> sources, String... options) {
        List<String> all = new ArrayList<>(List.of("-A" + AgenticProcessor.OPT_API_MAJOR + "=" + major));
        all.addAll(List.of(options));
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).withOptions(all).compile(sources);
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

    /** The IR of {@code Order} with {@code members} and {@code OrderService}, at {@code major}. */
    static ContractIr ir(int major, String members) {
        return parse(irJson(major, members));
    }

    static ContractIr parse(String json) {
        try {
            return IrJson.parse(json, "test");
        } catch (IrJson.IrReadException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A published release: it earns deprecation credit. */
    static ReleasePolicy.Release release(String version, ContractIr ir) {
        return new ReleasePolicy.Release(ReleaseVersion.parse(version), ir, true);
    }

    /** An unpublished (pending) release: part of the history chain, but earns no credit. */
    static ReleasePolicy.Release pending(String version, ContractIr ir) {
        return new ReleasePolicy.Release(ReleaseVersion.parse(version), ir, false);
    }

    /**
     * A {@link ContractRelease.Request#artifacts()} map for {@code ContractRelease.release}: the
     * OpenAPI document of {@code ir}'s {@code apiMajor}, and/or the MCP tool specifications, each
     * only when its text is not {@code null}.
     */
    static Map<String, byte[]> artifacts(String ir, String openApi, String mcpTools) {
        Map<String, byte[]> artifacts = new LinkedHashMap<>();
        if (openApi != null) {
            artifacts.put(ContractRelease.openApiFile(parse(ir).apiMajor()), openApi.getBytes(StandardCharsets.UTF_8));
        }
        if (mcpTools != null) {
            artifacts.put(ContractRelease.MCP_TOOLS_FILE, mcpTools.getBytes(StandardCharsets.UTF_8));
        }
        return artifacts;
    }
}
