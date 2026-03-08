/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for entity cross-reference mapping in generated DTOs:
 * wildcard type args, non-generic collection subtypes, and edge cases.
 */
class EntityRefMappingTest {

    @Test
    void mapsWildcardExtendsEntityToDto() {
        JavaFileObject item = JavaFileObjects.forSourceString("test.Item", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Item {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }""");
        JavaFileObject box = JavaFileObjects.forSourceString("test.Box", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.List;
                @AgentVisibleClass public class Box {
                    @AgentVisible(description = "ID") private Long id;
                    @AgentVisible(description = "items") private List<? extends Item> items;
                    public Long getId() { return id; }
                    public List<? extends Item> getItems() { return items; }
                }""");

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(item, box);
        CompilationSubject.assertThat(compilation).succeeded();

        String dto = getGeneratedSource(compilation, "test.generated.BoxDto");
        assertThat(dto).contains("List<ItemDto> items");
        assertThat(dto).contains("ItemDto::fromEntity");
    }

    @Test
    void mapsNonGenericCollectionSubtypeToDto() {
        JavaFileObject item = JavaFileObjects.forSourceString("test.Item", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Item {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }""");
        JavaFileObject itemBag = JavaFileObjects.forSourceString("test.ItemBag", """
                package test;
                import java.util.ArrayList;
                public class ItemBag extends ArrayList<Item> {}
                """);
        JavaFileObject holder = JavaFileObjects.forSourceString("test.Holder", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Holder {
                    @AgentVisible(description = "ID") private Long id;
                    @AgentVisible(description = "bag") private ItemBag bag;
                    public Long getId() { return id; }
                    public ItemBag getBag() { return bag; }
                }""");

        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .compile(item, itemBag, holder);
        CompilationSubject.assertThat(compilation).succeeded();

        String dto = getGeneratedSource(compilation, "test.generated.HolderDto");
        assertThat(dto).contains("List<ItemDto> bag");
        assertThat(dto).contains("ItemDto::fromEntity");
    }

    @Test
    void skipsUnboundedWildcardCollection() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Misc", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.List;
                @AgentVisibleClass public class Misc {
                    @AgentVisible(description = "ID") private Long id;
                    @AgentVisible(description = "wild") private List<?> wild;
                    public Long getId() { return id; }
                    public List<?> getWild() { return wild; }
                }""");

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(entity);
        CompilationSubject.assertThat(compilation).succeeded();

        String dto = getGeneratedSource(compilation, "test.generated.MiscDto");
        // Unbounded wildcard stays as-is (no entity mapping)
        assertThat(dto).contains("List<?> wild");
        assertThat(dto).doesNotContain("::fromEntity");
        assertThat(dto).doesNotContain("_visiting");
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
