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

/**
 * Tests for version attribute inheritance: method-level overrides class-level,
 * class-level overrides framework defaults. Also covers inheritance of existing
 * attributes (returnType, description, channels) when only version attrs are
 * specified at method level.
 */
class VersionInheritanceTest {

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

    // ---- Version inheritance (8 tests) ----

    @Test
    void classApiSince_inheritedByMethod() {
        // Class apiSince=2, method has no version override → method excluded at major=1
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class, apiSince = 2)
                public class OrderService {
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        // No methods active at major=1 → controller not generated
        var file = compilation.generatedSourceFile("test.generated.OrderServiceRestController");
        org.assertj.core.api.Assertions.assertThat(file).isEmpty();
    }

    @Test
    void methodOverridesClassApiSince() {
        // Class apiSince=2, method apiSince=1 → method included at major=1
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class, apiSince = 2)
                public class OrderService {
                    @AgenticExposed(description = "Find by ID", returnType = Order.class, apiSince = 1)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceRestController")
                .contentsAsUtf8String().contains("findById");
    }

    @Test
    void methodOverridesClassApiUntil() {
        // Class apiUntil=3, method apiUntil=MAX → method included at major=4
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class, apiUntil = 3)
                public class OrderService {
                    @AgenticExposed(description = "Find by ID", returnType = Order.class,
                                    apiUntil = 2147483647)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=4")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceRestController")
                .contentsAsUtf8String().contains("findById");
    }

    @Test
    void classDeprecation_inheritedByMethod() {
        // Class apiDeprecatedSince=2, method has no override → deprecated at major=2
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class, apiDeprecatedSince = 2)
                public class OrderService {
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceRestController")
                .contentsAsUtf8String().contains("@Deprecated");
    }

    @Test
    void methodClearsClassDeprecation() {
        // Class apiDeprecatedSince=2, method apiDeprecatedSince=0 → NOT deprecated at major=2
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class, apiDeprecatedSince = 2)
                public class OrderService {
                    @AgenticExposed(description = "Find by ID", returnType = Order.class,
                                    apiDeprecatedSince = 0)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceRestController")
                .contentsAsUtf8String().doesNotContain("@Deprecated");
    }

    @Test
    void classReplacement_inheritedByMethod() {
        // Class apiReplacement="findV2", apiDeprecatedSince=1, method has no override
        // → "findV2" appears in MCP tool description at major=2
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class,
                                apiDeprecatedSince = 1, apiReplacement = "findV2")
                public class OrderService {
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceMcpTool")
                .contentsAsUtf8String().contains("findV2");
    }

    @Test
    void methodClearsClassReplacement() {
        // Class apiReplacement="findV2", method apiReplacement="" → no "findV2" in description
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class,
                                apiDeprecatedSince = 1, apiReplacement = "findV2")
                public class OrderService {
                    @AgenticExposed(description = "Find by ID", returnType = Order.class,
                                    apiReplacement = "")
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceMcpTool")
                .contentsAsUtf8String().doesNotContain("findV2");
    }

    @Test
    void noClassAnnotation_frameworkDefaultsApply() {
        // Method-only annotation with no version attrs → active at major=1 (framework default)
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class OrderService {
                    @AgenticExposed(description = "Find by ID", returnType = Order.class)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceRestController")
                .contentsAsUtf8String().contains("findById");
    }

    // ---- Existing-attribute inheritance (6 tests) ----

    @Test
    void versionOnlyMethodOverride_inheritsClassReturnType() {
        // Class returnType=Order.class, method only has apiSince=2, major=2 → DTO in generated code
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(apiSince = 2)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceRestController")
                .contentsAsUtf8String().contains("OrderDto");
    }

    @Test
    void versionOnlyMethodOverride_inheritsClassDescription() {
        // Class description="Order ops", method only has apiSince=2 → "Order ops" in generated source
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(apiSince = 2)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceMcpTool")
                .contentsAsUtf8String().contains("Order ops");
    }

    @Test
    void versionOnlyMethodOverride_inheritsClassChannels() {
        // Class channels={Channel.API}, method only has apiSince=2
        // → method in REST controller but NOT in MCP tool
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class,
                                channels = { AgenticExposed.Channel.API })
                public class OrderService {
                    @AgenticExposed(apiSince = 2)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        // REST controller should be generated with the method
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceRestController")
                .contentsAsUtf8String().contains("findById");
        // MCP tool should NOT be generated (API-only channel)
        var mcpFile = compilation.generatedSourceFile("test.generated.OrderServiceMcpTool");
        org.assertj.core.api.Assertions.assertThat(mcpFile).isEmpty();
    }

    @Test
    void methodCanNarrowClassChannels() {
        // Class channels={AI, API}, method channels={API} → REST controller has method, MCP tool does not
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Find by ID", returnType = Order.class,
                                    channels = { AgenticExposed.Channel.API })
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceRestController")
                .contentsAsUtf8String().contains("findById");
        var mcpFile = compilation.generatedSourceFile("test.generated.OrderServiceMcpTool");
        org.assertj.core.api.Assertions.assertThat(mcpFile).isEmpty();
    }

    @Test
    void methodCannotWidenClassChannels() {
        // Class channels={API}, method channels={AI} → compile error
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class,
                                channels = { AgenticExposed.Channel.API })
                public class OrderService {
                    @AgenticExposed(description = "Find by ID", returnType = Order.class,
                                    channels = { AgenticExposed.Channel.AI })
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, service);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("must be a subset");
    }

    @Test
    void methodVersionOverride_preservesDtoMapping() {
        // Class returnType=Order.class, method only has apiSince=2, major=2
        // → generated REST controller source contains "OrderDto.fromEntity"
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(apiSince = 2)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceRestController")
                .contentsAsUtf8String().contains("OrderDto.fromEntity");
    }
}
