/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

class VersionConfigOpenApiTest {

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
    void customInfoVersion_appearsInSpec() {
        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions(
                        "-Aai.atlas.api.major=2",
                        "-Aai.atlas.openapi.infoVersion=2.1.0")
                .compile(ENTITY, SERVICE);

        String json = getGeneratedResource(compilation);
        assertThat(json).contains("\"version\" : \"2.1.0\"");
    }

    @Test
    void customMajorAndBasePath_inPaths() {
        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions(
                        "-Aai.atlas.api.basePath=/rest",
                        "-Aai.atlas.api.major=3",
                        "-Aai.atlas.openapi.infoVersion=3.0.0")
                .compile(ENTITY, SERVICE);

        String json = getGeneratedResource(compilation);
        assertThat(json).contains("/rest/v3/order-service/find-by-id");
        assertThat(json).doesNotContain("/api/v1/");
    }

    @Test
    void defaults_matchCurrentBehavior() {
        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, SERVICE);

        String json = getGeneratedResource(compilation);
        assertThat(json).contains("\"version\" : \"1.0.0\"");
        assertThat(json).contains("/api/v1/order-service/find-by-id");
    }

    private static String getGeneratedResource(Compilation compilation) {
        var file = compilation.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/openapi/openapi.json");
        assertThat(file).as("OpenAPI spec resource file").isPresent();
        try {
            return file.get().getCharContent(true).toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
