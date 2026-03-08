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

class EmptyEntityTest {

    @Test
    void warnsWhenNoAgentVisibleFields() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.EmptyEntity",
                """
                package test;

                import com.egoge.ai.atlas.annotations.AgentVisibleClass;

                @AgentVisibleClass
                public class EmptyEntity {
                    private String name;
                    private String email;

                    public String getName() { return name; }
                    public String getEmail() { return email; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(compilation).hadWarningContaining(
                "has no @AgentVisible fields — no DTO will be generated");
    }

    @Test
    void doesNotGenerateDtoForEmptyEntity() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.EmptyEntity2",
                """
                package test;

                import com.egoge.ai.atlas.annotations.AgentVisibleClass;

                @AgentVisibleClass
                public class EmptyEntity2 {
                    private String internalField;

                    public String getInternalField() { return internalField; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        assertThat(compilation).succeeded();

        // No DTO should be generated
        compilation.generatedSourceFiles().forEach(f -> {
            assert !f.getName().contains("EmptyEntity2Dto") :
                    "Should not generate DTO for entity with no @AgentVisible fields";
        });
    }
}
