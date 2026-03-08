/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for edge cases: static inner classes, abstract classes,
 * interfaces, enums, enum fields, and multi-round idempotency.
 */
class EdgeCaseTest {

    @Test
    void generatesForStaticInnerClass() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Outer",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisible;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;

                public class Outer {
                    @AgentVisibleClass
                    public static class Inner {
                        @AgentVisible(description = "ID") private Long id;
                        public Long getId() { return id; }
                    }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        String dto = getGeneratedSource(compilation, "test.generated.InnerDto");
        assertThat(dto).contains("record InnerDto");
        assertThat(dto).contains("Long id");
        assertThat(dto).contains("Outer.Inner entity");
    }

    @Test
    void warnsOnInterface() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.MyInterface",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;

                @AgentVisibleClass
                public interface MyInterface {
                    String getName();
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        CompilationSubject.assertThat(compilation).succeeded();
        CompilationSubject.assertThat(compilation).hadWarningContaining("interface");
        CompilationSubject.assertThat(compilation).hadWarningContaining("not supported");
        // No DTO should be generated
        assertThat(compilation.generatedSourceFile("test.generated.MyInterfaceDto")).isEmpty();
    }

    @Test
    void warnsOnEnum() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Status",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;

                @AgentVisibleClass
                public enum Status {
                    ACTIVE, INACTIVE;
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        CompilationSubject.assertThat(compilation).succeeded();
        CompilationSubject.assertThat(compilation).hadWarningContaining("enum");
        CompilationSubject.assertThat(compilation).hadWarningContaining("not supported");
    }

    @Test
    void warnsOnAbstractClass() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.AbstractBase",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisible;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;

                @AgentVisibleClass
                public abstract class AbstractBase {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        CompilationSubject.assertThat(compilation).succeeded();
        CompilationSubject.assertThat(compilation).hadWarningContaining("abstract");
        // DTO should still be generated (abstract classes can have fields)
        String dto = getGeneratedSource(compilation, "test.generated.AbstractBaseDto");
        assertThat(dto).contains("Long id");
    }

    @Test
    void handlesEnumTypeField() {
        JavaFileObject enumType = JavaFileObjects.forSourceString("test.OrderStatus",
                """
                package test;
                public enum OrderStatus { PENDING, SHIPPED, DELIVERED }
                """);

        JavaFileObject entity = JavaFileObjects.forSourceString("test.OrderEntity",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisible;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class OrderEntity {
                    @AgentVisible(description = "ID") private Long id;
                    @AgentVisible(description = "Status") private OrderStatus status;
                    public Long getId() { return id; }
                    public OrderStatus getStatus() { return status; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(enumType, entity);

        String dto = getGeneratedSource(compilation, "test.generated.OrderEntityDto");
        assertThat(dto).contains("OrderStatus status");
        assertThat(dto).contains("entity.getStatus()");

        // Verify enum valid values are captured in FIELD_METADATA
        assertThat(dto).contains("FIELD_METADATA");
        assertThat(dto).contains("\"PENDING\"");
        assertThat(dto).contains("\"SHIPPED\"");
        assertThat(dto).contains("\"DELIVERED\"");
    }

    @Test
    void handlesMultipleAnnotatedClassesInSameRound() {
        JavaFileObject entity1 = JavaFileObjects.forSourceString("test.Alpha",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisible;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Alpha {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);

        JavaFileObject entity2 = JavaFileObjects.forSourceString("test.Beta",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisible;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Beta {
                    @AgentVisible(description = "Name") private String name;
                    public String getName() { return name; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(entity1, entity2);

        assertThat(compilation.generatedSourceFile("test.generated.AlphaDto")).isPresent();
        assertThat(compilation.generatedSourceFile("test.generated.BetaDto")).isPresent();

        // Single OpenAPI spec should contain both schemas
        var openapi = compilation.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/openapi/openapi.json");
        assertThat(openapi).isPresent();
        try {
            String json = openapi.get().getCharContent(true).toString();
            assertThat(json).contains("\"AlphaDto\"");
            assertThat(json).contains("\"BetaDto\"");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void handsBooleanFieldWithIsGetter() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Toggleable",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisible;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Toggleable {
                    @AgentVisible(description = "Active flag") private boolean active;
                    public boolean isActive() { return active; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        String dto = getGeneratedSource(compilation, "test.generated.ToggleableDto");
        assertThat(dto).contains("boolean active");
        assertThat(dto).contains("entity.isActive()");
    }

    private static String getGeneratedSource(Compilation compilation, String qualifiedName) {
        var file = compilation.generatedSourceFile(qualifiedName);
        assertThat(file).as("Generated file: " + qualifiedName).isPresent();
        try {
            return file.get().getCharContent(true).toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
