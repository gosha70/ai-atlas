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
        assertThat(dto).contains("ItemDto.fromEntity((Item) e)");
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
        assertThat(dto).contains("ItemDto.fromEntity((Item) e)");
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
        // Unbounded wildcard stays as-is (no entity mapping — no Dto::fromEntity call)
        assertThat(dto).contains("List<?> wild");
        assertThat(dto).doesNotContain("Dto.fromEntity(");
        assertThat(dto).doesNotContain("_visiting");
    }

    @Test
    void mapsRawCollectionWithTypeHint() {
        JavaFileObject item = JavaFileObjects.forSourceString("test.Item", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Item {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }""");
        JavaFileObject holder = JavaFileObjects.forSourceString("test.Holder", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.Collection;
                @AgentVisibleClass public class Holder {
                    @AgentVisible(description = "ID") private Long id;
                    @AgentVisible(description = "items", type = Item.class)
                    @SuppressWarnings("rawtypes")
                    private Collection items;
                    public Long getId() { return id; }
                    @SuppressWarnings("rawtypes")
                    public Collection getItems() { return items; }
                }""");

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(item, holder);
        CompilationSubject.assertThat(compilation).succeeded();

        String dto = getGeneratedSource(compilation, "test.generated.HolderDto");
        assertThat(dto).contains("List<ItemDto> items");
        assertThat(dto).contains("ItemDto.fromEntity((Item) e)");
    }

    @Test
    void rawCollectionWithoutHintPassesThrough() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Misc", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.Collection;
                @AgentVisibleClass public class Misc {
                    @AgentVisible(description = "ID") private Long id;
                    @AgentVisible(description = "stuff")
                    @SuppressWarnings("rawtypes")
                    private Collection stuff;
                    public Long getId() { return id; }
                    @SuppressWarnings("rawtypes")
                    public Collection getStuff() { return stuff; }
                }""");

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(entity);
        CompilationSubject.assertThat(compilation).succeeded();

        String dto = getGeneratedSource(compilation, "test.generated.MiscDto");
        // Raw collection without hint stays as-is (no entity-ref mapping)
        assertThat(dto).contains("Collection stuff");
        assertThat(dto).doesNotContain("Dto.fromEntity(");
    }

    @Test
    void hintOnNonCollectionFieldEmitsWarning() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Bad", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Bad {
                    @AgentVisible(description = "ID", type = Bad.class) private Long id;
                    public Long getId() { return id; }
                }""");

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(entity);
        CompilationSubject.assertThat(compilation).succeeded();
        CompilationSubject.assertThat(compilation).hadWarningContaining(
                "@AgentVisible(type = ...) on non-collection field");
    }

    @Test
    void incompatibleTypeHintEmitsWarning() {
        JavaFileObject item = JavaFileObjects.forSourceString("test.Item", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Item {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }""");
        JavaFileObject other = JavaFileObjects.forSourceString("test.Other", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Other {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }""");
        JavaFileObject holder = JavaFileObjects.forSourceString("test.Holder", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.List;
                @AgentVisibleClass public class Holder {
                    @AgentVisible(description = "ID") private Long id;
                    @AgentVisible(description = "items", type = Other.class)
                    private List<Item> items;
                    public Long getId() { return id; }
                    public List<Item> getItems() { return items; }
                }""");

        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .compile(item, other, holder);
        CompilationSubject.assertThat(compilation).failed();
        CompilationSubject.assertThat(compilation).hadErrorContaining(
                "is not assignable from");
    }

    @Test
    void typeHintUpcast_subToSuper_passes() {
        // Raw Collection + type=Base on field holding Internal instances → OK
        // Internal is-a Base, so the cast in generated code (Base) is safe
        JavaFileObject base = JavaFileObjects.forSourceString("test.Base", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Base {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }""");
        JavaFileObject holder = JavaFileObjects.forSourceString("test.Holder", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.Collection;
                @AgentVisibleClass public class Holder {
                    @AgentVisible(description = "ID") private Long id;
                    @AgentVisible(description = "items", type = Base.class)
                    @SuppressWarnings("rawtypes")
                    private Collection items;
                    public Long getId() { return id; }
                    @SuppressWarnings("rawtypes")
                    public Collection getItems() { return items; }
                }""");

        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .compile(base, holder);
        CompilationSubject.assertThat(compilation).succeeded();
        String dto = getGeneratedSource(compilation, "test.generated.HolderDto");
        assertThat(dto).contains("List<BaseDto> items");
    }

    @Test
    void typeHintDowncast_superToSub_failsWithError() {
        // List<Base> + type=Internal → ERROR (would cast Base → Internal)
        JavaFileObject base = JavaFileObjects.forSourceString("test.Base", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Base {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }""");
        JavaFileObject internal = JavaFileObjects.forSourceString("test.Internal", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Internal extends Base {
                    @AgentVisible(description = "label") private String label;
                    public String getLabel() { return label; }
                }""");
        JavaFileObject holder = JavaFileObjects.forSourceString("test.Holder", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.List;
                @AgentVisibleClass public class Holder {
                    @AgentVisible(description = "ID") private Long id;
                    @AgentVisible(description = "items", type = Internal.class)
                    private List<Base> items;
                    public Long getId() { return id; }
                    public List<Base> getItems() { return items; }
                }""");

        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .compile(base, internal, holder);
        CompilationSubject.assertThat(compilation).failed();
        CompilationSubject.assertThat(compilation).hadErrorContaining(
                "is not assignable from");
    }

    @Test
    void typeHintDowncast_arraySuperToSub_failsWithError() {
        // Base[] + type=Internal → ERROR (would cast Base → Internal)
        JavaFileObject base = JavaFileObjects.forSourceString("test.Base", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Base {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }""");
        JavaFileObject internal = JavaFileObjects.forSourceString("test.Internal", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Internal extends Base {}
                """);
        JavaFileObject holder = JavaFileObjects.forSourceString("test.Holder", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Holder {
                    @AgentVisible(description = "ID") private Long id;
                    @AgentVisible(description = "items", type = Internal.class)
                    private Base[] items;
                    public Long getId() { return id; }
                    public Base[] getItems() { return items; }
                }""");

        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .compile(base, internal, holder);
        CompilationSubject.assertThat(compilation).failed();
        CompilationSubject.assertThat(compilation).hadErrorContaining(
                "is not assignable from");
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
