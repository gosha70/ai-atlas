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
    void warnsOnAgenticEntityOnInterface() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.BadInterface",
                """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticEntity;

                @AgenticEntity
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
    void errorOnDuplicateAgenticFieldName() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.DuplicateNames",
                """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;

                @AgenticEntity
                public class DuplicateNames {
                    @AgenticField(name = "amount", description = "Price")
                    private Long price;

                    @AgenticField(name = "amount", description = "Cost")
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

                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;

                @AgenticEntity
                public class ImplicitDuplicate {
                    @AgenticField(description = "ID")
                    private Long id;

                    @AgenticField(name = "id", description = "Identifier alias")
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
    void warnsOnAgenticEntityOnEnum() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.BadEnum",
                """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticEntity;

                @AgenticEntity
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
