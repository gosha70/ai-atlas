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

class VersionFilteringRestControllerTest {

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
    void methodNotYetIntroduced_excludedFromController() {
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
        // All methods excluded at major=1 — no REST controller generated
        var file = compilation.generatedSourceFile("test.generated.OrderServiceRestController");
        org.assertj.core.api.Assertions.assertThat(file).isEmpty();
    }

    @Test
    void methodIntroduced_includedInController() {
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
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceRestController")
                .contentsAsUtf8String().contains("findById");
    }

    @Test
    void deprecatedMethod_hasDeprecatedAnnotation() {
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
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceRestController")
                .contentsAsUtf8String().contains("@Deprecated");
    }

    @Test
    void retiredMethod_excludedFromController() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Find by ID", returnType = Order.class,
                                    apiSince = 1, apiUntil = 1)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        // All methods excluded — no REST controller generated
        var file = compilation.generatedSourceFile("test.generated.OrderServiceRestController");
        org.assertj.core.api.Assertions.assertThat(file).isEmpty();
    }

    @Test
    void defaultAnnotation_unchangedOutput() {
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
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceRestController")
                .contentsAsUtf8String().contains("findById");
    }
}
