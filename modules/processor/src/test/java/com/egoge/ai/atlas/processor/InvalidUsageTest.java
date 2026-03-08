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

class InvalidUsageTest {

    @Test
    void warnsOnAgentVisibleClassOnInterface() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.BadInterface",
                """
                package test;

                import com.egoge.ai.atlas.annotations.AgentVisibleClass;

                @AgentVisibleClass
                public interface BadInterface {
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(compilation).hadWarningContaining("interface");
        assertThat(compilation).hadWarningContaining("not supported");
    }

    @Test
    void errorOnDuplicateAgentVisibleName() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.DuplicateNames",
                """
                package test;

                import com.egoge.ai.atlas.annotations.AgentVisible;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;

                @AgentVisibleClass
                public class DuplicateNames {
                    @AgentVisible(name = "amount", description = "Price")
                    private Long price;

                    @AgentVisible(name = "amount", description = "Cost")
                    private Long cost;

                    public Long getPrice() { return price; }
                    public Long getCost() { return cost; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("\"amount\"");
        assertThat(compilation).hadErrorContaining("conflicts with field");
    }

    @Test
    void errorOnImplicitDuplicateWithExplicitName() {
        // Field 'id' has no explicit name (defaults to "id"),
        // another field sets name = "id" — collision
        JavaFileObject source = JavaFileObjects.forSourceString("test.ImplicitDuplicate",
                """
                package test;

                import com.egoge.ai.atlas.annotations.AgentVisible;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;

                @AgentVisibleClass
                public class ImplicitDuplicate {
                    @AgentVisible(description = "ID")
                    private Long id;

                    @AgentVisible(name = "id", description = "Identifier alias")
                    private Long identifier;

                    public Long getId() { return id; }
                    public Long getIdentifier() { return identifier; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("\"id\"");
        assertThat(compilation).hadErrorContaining("conflicts with field");
    }

    @Test
    void warnsOnAgentVisibleClassOnEnum() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.BadEnum",
                """
                package test;

                import com.egoge.ai.atlas.annotations.AgentVisibleClass;

                @AgentVisibleClass
                public enum BadEnum {
                    A, B
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(compilation).hadWarningContaining("enum");
        assertThat(compilation).hadWarningContaining("not supported");
    }
}
