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
    void warnsOnAgentVisibleClassOnInterface() {
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

        assertThat(compilation).succeeded();
        assertThat(compilation).hadWarningContaining("interface");
        assertThat(compilation).hadWarningContaining("not supported");
    }

    @Test
    void warnsOnAgentVisibleClassOnEnum() {
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

        assertThat(compilation).succeeded();
        assertThat(compilation).hadWarningContaining("enum");
        assertThat(compilation).hadWarningContaining("not supported");
    }
}
