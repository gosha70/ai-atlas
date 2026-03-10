/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

class VersionFilteringMcpToolTest {

    private static final JavaFileObject ENTITY = JavaFileObjects.forSourceString("test.Order",
            """
            package test;
            import com.egoge.ai.atlas.annotations.AgenticField;
            import com.egoge.ai.atlas.annotations.AgenticEntity;
            @AgenticEntity
            public class Order {
                @AgenticField(description = "ID") private Long id;
                public Long getId() { return id; }
            }
            """);

    @Test
    void methodNotYetIntroduced_excludedFromTool() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Find by ID", returnType = Order.class, apiSince = 2)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        // All AI methods excluded — no MCP tool generated
        var file = compilation.generatedSourceFile("test.generated.OrderServiceMcpTool");
        org.assertj.core.api.Assertions.assertThat(file).isEmpty();
    }

    @Test
    void deprecatedMethod_hasEnrichedDescription() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Find by ID", returnType = Order.class, apiDeprecatedSince = 1)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceMcpTool")
                .contentsAsUtf8String().contains("[DEPRECATED since v1]");
    }

    @Test
    void deprecatedWithReplacement_includesReplacement() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Find by ID", returnType = Order.class,
                                    apiDeprecatedSince = 1, apiReplacement = "findV2")
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceMcpTool")
                .contentsAsUtf8String().contains("use findV2");
    }

    @Test
    void sinceV2Method_hasSincePrefix() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Find by ID", returnType = Order.class, apiSince = 2)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceMcpTool")
                .contentsAsUtf8String().contains("[Since v2]");
    }

    @Test
    void defaultAnnotation_noPrefix() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceMcpTool")
                .contentsAsUtf8String().doesNotContain("[DEPRECATED");
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceMcpTool")
                .contentsAsUtf8String().doesNotContain("[Since v");
    }
}
