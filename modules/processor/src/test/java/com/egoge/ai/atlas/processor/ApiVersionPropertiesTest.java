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

class ApiVersionPropertiesTest {

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

    private static String getPropertiesContent(Compilation compilation) {
        var file = compilation.generatedFile(
                StandardLocation.CLASS_OUTPUT, "META-INF/ai-atlas/api-version.properties");
        assertThat(file).as("api-version.properties").isPresent();
        try {
            return file.get().getCharContent(true).toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void propertiesFileGenerated_containsConfiguredValues() {
        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions(
                        "-Aai.atlas.api.major=2",
                        "-Aai.atlas.api.basePath=/services")
                .compile(ENTITY);

        assertThat(compilation).succeeded();
        String content = getPropertiesContent(compilation);
        assertThat(content).contains("api.major=2");
        assertThat(content).contains("api.basePath=/services");
    }

    @Test
    void propertiesFileGenerated_defaultValues() {
        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY);

        assertThat(compilation).succeeded();
        String content = getPropertiesContent(compilation);
        assertThat(content).contains("api.major=1");
        assertThat(content).contains("api.basePath=/api");
    }
}
