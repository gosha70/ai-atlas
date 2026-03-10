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

class VersionFilteringOpenApiTest {

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
    void methodNotYetIntroduced_excludedFromSpec() {
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
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/openapi/openapi.json")
                .contentsAsUtf8String().doesNotContain("find-by-id");
    }

    @Test
    void deprecatedMethod_hasDeprecatedFlag() {
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
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/openapi/openapi-v2.json")
                .contentsAsUtf8String().contains("\"deprecated\" : true");
    }

    @Test
    void versionedSpecFile_generated() {
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
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        var versionedFile = compilation.generatedFile(
                StandardLocation.CLASS_OUTPUT, "META-INF/openapi/openapi-v2.json");
        org.assertj.core.api.Assertions.assertThat(versionedFile)
                .as("Versioned OpenAPI spec openapi-v2.json should be generated").isPresent();
    }

    @Test
    void legacySpecFile_generatedAsAlias() {
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
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
        var legacyFile = compilation.generatedFile(
                StandardLocation.CLASS_OUTPUT, "META-INF/openapi/openapi.json");
        org.assertj.core.api.Assertions.assertThat(legacyFile)
                .as("Legacy OpenAPI spec openapi.json should be generated as alias").isPresent();

        // Both files should contain the same path
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/openapi/openapi.json")
                .contentsAsUtf8String().contains("find-by-id");
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/openapi/openapi-v2.json")
                .contentsAsUtf8String().contains("find-by-id");
    }

    @Test
    void defaultAnnotation_unchangedPaths() {
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
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/openapi/openapi.json")
                .contentsAsUtf8String().contains("find-by-id");
    }
}
