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

class VersionConfigEndToEndTest {

    @Test
    void endToEnd_customBasePathAndMajor_flowsThroughAllGenerators() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Order",
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
                .withOptions(
                        "-Aai.atlas.api.basePath=/services",
                        "-Aai.atlas.api.major=2",
                        "-Aai.atlas.openapi.infoVersion=2.1.0")
                .compile(entity, service);

        assertThat(compilation).succeeded();

        // REST controller uses custom path
        String controllerSource = getGeneratedSource(compilation,
                "test.generated.OrderServiceRestController");
        org.assertj.core.api.Assertions.assertThat(controllerSource)
                .contains("@RequestMapping(\"/services/v2/order-service\")");
        org.assertj.core.api.Assertions.assertThat(controllerSource)
                .contains("@PostMapping(\"/find-by-id\")");

        // OpenAPI spec uses custom path and info.version
        String openApiJson = getGeneratedResource(compilation);
        org.assertj.core.api.Assertions.assertThat(openApiJson)
                .contains("/services/v2/order-service/find-by-id");
        org.assertj.core.api.Assertions.assertThat(openApiJson)
                .contains("\"version\" : \"2.1.0\"");

        // Verify NO residual /api/v1/ references in generated output
        org.assertj.core.api.Assertions.assertThat(controllerSource).doesNotContain("/api/v1/");
        org.assertj.core.api.Assertions.assertThat(openApiJson).doesNotContain("/api/v1/");
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

    private static String getGeneratedResource(Compilation compilation) {
        var file = compilation.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/openapi/openapi.json");
        org.assertj.core.api.Assertions.assertThat(file).as("OpenAPI spec resource file").isPresent();
        try {
            return file.get().getCharContent(true).toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
