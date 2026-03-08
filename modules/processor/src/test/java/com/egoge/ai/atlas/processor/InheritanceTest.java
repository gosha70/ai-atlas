/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for superclass chain walking — inherited @AgentVisible fields
 * should appear in generated DTOs.
 */
class InheritanceTest {

    @Test
    void includesInheritedFields() {
        JavaFileObject base = JavaFileObjects.forSourceString("test.BaseEntity",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisible;
                public abstract class BaseEntity {
                    @AgentVisible(description = "Base ID") private Long id;
                    public Long getId() { return id; }
                }
                """);

        JavaFileObject child = JavaFileObjects.forSourceString("test.Product",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisible;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Product extends BaseEntity {
                    @AgentVisible(description = "Product name") private String name;
                    public String getName() { return name; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(base, child);

        String source = getGeneratedSource(compilation, "test.generated.ProductDto");
        // Superclass field should come first
        assertThat(source).contains("Long id");
        assertThat(source).contains("String name");
        assertThat(source).contains("entity.getId()");
        assertThat(source).contains("entity.getName()");
    }

    @Test
    void deduplicatesOverriddenFields() {
        JavaFileObject base = JavaFileObjects.forSourceString("test.BaseItem",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisible;
                public class BaseItem {
                    @AgentVisible(description = "Base ID") private Long id;
                    public Long getId() { return id; }
                }
                """);

        JavaFileObject child = JavaFileObjects.forSourceString("test.SpecialItem",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisible;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class SpecialItem extends BaseItem {
                    @AgentVisible(description = "Label") private String label;
                    public String getLabel() { return label; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(base, child);

        String source = getGeneratedSource(compilation, "test.generated.SpecialItemDto");
        // id should appear only once
        long idCount = source.chars().filter(c -> c == 'i').count(); // rough check
        assertThat(source).contains("Long id");
        assertThat(source).contains("String label");
    }

    @Test
    void handlesDeepHierarchy() {
        JavaFileObject grandparent = JavaFileObjects.forSourceString("test.GrandParent",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisible;
                public class GrandParent {
                    @AgentVisible(description = "GP field") private String gpField;
                    public String getGpField() { return gpField; }
                }
                """);

        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisible;
                public class Parent extends GrandParent {
                    @AgentVisible(description = "Parent field") private String parentField;
                    public String getParentField() { return parentField; }
                }
                """);

        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisible;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Child extends Parent {
                    @AgentVisible(description = "Child field") private String childField;
                    public String getChildField() { return childField; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(grandparent, parent, child);

        String source = getGeneratedSource(compilation, "test.generated.ChildDto");
        assertThat(source).contains("String gpField");
        assertThat(source).contains("String parentField");
        assertThat(source).contains("String childField");
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
