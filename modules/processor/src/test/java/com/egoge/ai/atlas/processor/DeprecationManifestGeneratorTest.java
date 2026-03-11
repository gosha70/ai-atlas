/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compile-testing tests for {@link com.egoge.ai.atlas.processor.generator.DeprecationManifestGenerator}.
 */
class DeprecationManifestGeneratorTest {

    private static final JavaFileObject ENTITY = JavaFileObjects.forSourceString("test.Order",
            """
            package test;
            import com.egoge.ai.atlas.annotations.*;
            @AgenticEntity
            public class Order {
                @AgenticField(description = "ID") private Long id;
                public Long getId() { return id; }
            }
            """);

    private static String getManifestContent(Compilation compilation) {
        var file = compilation.generatedFile(
                StandardLocation.CLASS_OUTPUT, "META-INF/ai-atlas/deprecation-manifest.json");
        assertThat(file).as("deprecation-manifest.json").isPresent();
        try {
            return file.get().getCharContent(true).toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void manifestGenerated_containsDeprecatedEndpoints() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Find by ID", returnType = Order.class,
                                    apiDeprecatedSince = 1)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        String manifest = getManifestContent(compilation);
        assertThat(manifest).contains("\"method\":\"POST\"");
        assertThat(manifest).contains("\"deprecated\":true");
        assertThat(manifest).contains("\"deprecatedSince\":1");
    }

    @Test
    void manifestGenerated_recordsHttpMethod() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    // zero-param → GET
                    public Order findAll() { return null; }
                    // one-param → POST
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        String manifest = getManifestContent(compilation);
        assertThat(manifest).contains("\"method\":\"GET\"");
        assertThat(manifest).contains("\"method\":\"POST\"");
        assertThat(manifest).contains("find-all");
        assertThat(manifest).contains("find-by-id");
    }

    @Test
    void manifestGenerated_excludesFilteredMethods() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    // apiSince=3 is not active at major=2
                    @AgenticExposed(description = "Future method", returnType = Order.class,
                                    apiSince = 3)
                    public Order futureMethod(Long id) { return null; }
                    // This one is active
                    public Order findAll() { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        String manifest = getManifestContent(compilation);
        assertThat(manifest).doesNotContain("future-method");
        assertThat(manifest).contains("find-all");
    }

    @Test
    void manifestGenerated_excludesAiOnlyMethods() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class OrderService {
                    // AI channel only — should not appear in manifest
                    @AgenticExposed(description = "AI-only method", returnType = Order.class,
                                    channels = {AgenticExposed.Channel.AI})
                    public Order aiOnlyFind(Long id) { return null; }
                    // API channel — should appear in manifest
                    @AgenticExposed(description = "API method", returnType = Order.class,
                                    channels = {AgenticExposed.Channel.API})
                    public Order apiFind(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        String manifest = getManifestContent(compilation);
        assertThat(manifest).doesNotContain("ai-only-find");
        assertThat(manifest).contains("api-find");
    }

    @Test
    void manifestGenerated_replacementPopulatedWhenDeprecated() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Old method", returnType = Order.class,
                                    apiDeprecatedSince = 1, apiReplacement = "findV3")
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        String manifest = getManifestContent(compilation);
        assertThat(manifest).contains("\"replacement\":\"findV3\"");
    }

    @Test
    void manifestGenerated_multilineReplacementEscapedCorrectly() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Old", returnType = Order.class,
                                    apiDeprecatedSince = 1,
                                    apiReplacement = "Use findV3()\\ninstead")
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        String manifest = getManifestContent(compilation);
        // The newline must be escaped as \\n in JSON, not a raw newline
        assertThat(manifest).contains("\"replacement\":\"Use findV3()\\ninstead\"");
        assertThat(manifest).doesNotContain("\"replacement\":\"Use findV3()\ninstead\"");
    }
}
