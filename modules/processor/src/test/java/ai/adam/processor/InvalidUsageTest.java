/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

class InvalidUsageTest {

    @Test
    void rejectsAgentVisibleClassOnInterface() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.BadInterface",
                """
                package test;

                import ai.adam.annotations.AgentVisibleClass;

                @AgentVisibleClass
                public interface BadInterface {
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@AgentVisibleClass can only be applied to classes");
    }

    @Test
    void rejectsAgentVisibleClassOnEnum() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.BadEnum",
                """
                package test;

                import ai.adam.annotations.AgentVisibleClass;

                @AgentVisibleClass
                public enum BadEnum {
                    A, B
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@AgentVisibleClass can only be applied to classes");
    }
}
