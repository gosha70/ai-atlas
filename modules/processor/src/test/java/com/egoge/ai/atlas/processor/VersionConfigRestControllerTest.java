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

class VersionConfigRestControllerTest {

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

    private static final JavaFileObject SERVICE = JavaFileObjects.forSourceString("test.OrderService",
            """
            package test;
            import com.egoge.ai.atlas.annotations.AgenticExposed;
            @AgenticExposed(description = "Order ops", returnType = Order.class)
            public class OrderService {
                public Order findById(Long id) { return null; }
            }
            """);

    @Test
    void customMajorVersion_generatesV2Path() {
        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, SERVICE);

        assertThat(compilation).succeeded();
        String source = getGeneratedSource(compilation, "test.generated.OrderServiceRestController");
        org.assertj.core.api.Assertions.assertThat(source).contains("@RequestMapping(\"/api/v2/order-service\")");
    }

    @Test
    void customBasePath_generatesCustomPrefix() {
        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions(
                        "-Aai.atlas.api.basePath=/services",
                        "-Aai.atlas.api.major=3")
                .compile(ENTITY, SERVICE);

        assertThat(compilation).succeeded();
        String source = getGeneratedSource(compilation, "test.generated.OrderServiceRestController");
        org.assertj.core.api.Assertions.assertThat(source).contains("@RequestMapping(\"/services/v3/order-service\")");
    }

    @Test
    void noConfig_defaultsToV1() {
        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, SERVICE);

        assertThat(compilation).succeeded();
        String source = getGeneratedSource(compilation, "test.generated.OrderServiceRestController");
        org.assertj.core.api.Assertions.assertThat(source).contains("@RequestMapping(\"/api/v1/order-service\")");
    }

    private static String getGeneratedSource(Compilation compilation, String qualifiedName) {
        var file = compilation.generatedSourceFile(qualifiedName);
        org.assertj.core.api.Assertions.assertThat(file).as("Generated file: " + qualifiedName).isPresent();
        try {
            return file.get().getCharContent(true).toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
