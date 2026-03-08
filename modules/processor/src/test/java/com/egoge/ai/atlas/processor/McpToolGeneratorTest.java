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
 * Tests for McpToolGenerator. Generated MCP tool classes reference Spring AI
 * annotations which aren't on the test classpath, so we verify the generated
 * source content as strings rather than asserting full compilation success.
 */
class McpToolGeneratorTest {

    @Test
    void generatesToolClassWithServiceDelegation() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.MyEntity",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisible;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class MyEntity {
                    @AgentVisible(description = "ID") private Long id;
                    @AgentVisible(description = "Name") private String name;
                    public Long getId() { return id; }
                    public String getName() { return name; }
                }
                """);

        JavaFileObject service = JavaFileObjects.forSourceString("test.MyService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "My service operations", returnType = MyEntity.class)
                public class MyService {
                    public MyEntity findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(entity, service);

        String toolSource = getGeneratedSource(compilation, "test.generated.MyServiceMcpTool");
        assertThat(toolSource).contains("@Tool(");
        assertThat(toolSource).contains("MyEntityDto.fromEntity(service.findById(id))");
        assertThat(toolSource).contains("@Service");
        assertThat(toolSource).contains("private final MyService service");
    }

    @Test
    void generatesListMappingForCollectionReturn() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Item",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisible;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Item {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);

        JavaFileObject service = JavaFileObjects.forSourceString("test.ItemService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                import java.util.List;
                @AgenticExposed(description = "Item ops", returnType = Item.class)
                public class ItemService {
                    public List<Item> findAll() { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(entity, service);

        String toolSource = getGeneratedSource(compilation, "test.generated.ItemServiceMcpTool");
        assertThat(toolSource).contains("stream().map(e -> ItemDto.fromEntity((Item) e)).toList()");
        assertThat(toolSource).contains("List<ItemDto>");
    }

    @Test
    void generatesStreamSupportMappingForIterableReturn() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Item",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisible;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Item {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);

        JavaFileObject service = JavaFileObjects.forSourceString("test.ItemService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Item ops", returnType = Item.class)
                public class ItemService {
                    public Iterable<Item> findAll() { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(entity, service);

        String toolSource = getGeneratedSource(compilation, "test.generated.ItemServiceMcpTool");
        assertThat(toolSource).contains("StreamSupport.stream(service.findAll().spliterator(), false)");
        assertThat(toolSource).contains(".map(e -> ItemDto.fromEntity((Item) e)).toList()");
        assertThat(toolSource).contains("List<ItemDto>");
    }

    @Test
    void generatesArraysStreamMappingForArrayReturn() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Item",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisible;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Item {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);

        JavaFileObject service = JavaFileObjects.forSourceString("test.ItemService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Item ops", returnType = Item.class)
                public class ItemService {
                    public Item[] findAll() { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(entity, service);

        String toolSource = getGeneratedSource(compilation, "test.generated.ItemServiceMcpTool");
        assertThat(toolSource).contains("Arrays.stream(service.findAll())");
        assertThat(toolSource).contains(".map(e -> ItemDto.fromEntity((Item) e)).toList()");
        assertThat(toolSource).contains("List<ItemDto>");
    }

    @Test
    void usesMethodNameAsToolName() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Task",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgentVisible;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Task {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);

        JavaFileObject service = JavaFileObjects.forSourceString("test.TaskService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Task ops", returnType = Task.class)
                public class TaskService {
                    public Task getById(Long id) { return null; }
                    public Task create(String name) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(entity, service);

        String toolSource = getGeneratedSource(compilation, "test.generated.TaskServiceMcpTool");
        assertThat(toolSource).contains("name = \"getById\"");
        assertThat(toolSource).contains("name = \"create\"");
    }

    @Test
    void channelFiltering_aiOnly_generatesMcpTool() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Item",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Item {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);
        JavaFileObject service = JavaFileObjects.forSourceString("test.AiService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class AiService {
                    @AgenticExposed(description = "AI only", returnType = Item.class,
                                    channels = { AgenticExposed.Channel.AI })
                    public Item find() { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(entity, service);
        String toolSource = getGeneratedSource(compilation, "test.generated.AiServiceMcpTool");
        assertThat(toolSource).contains("@Tool(");
        assertThat(toolSource).contains("name = \"find\"");
    }

    @Test
    void channelFiltering_apiOnly_skipsMcpTool() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Item",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Item {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);
        JavaFileObject service = JavaFileObjects.forSourceString("test.ApiOnlyService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class ApiOnlyService {
                    @AgenticExposed(description = "API only", returnType = Item.class,
                                    channels = { AgenticExposed.Channel.API })
                    public Item find() { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(entity, service);
        var file = compilation.generatedSourceFile("test.generated.ApiOnlyServiceMcpTool");
        assertThat(file).as("MCP tool should NOT be generated for API-only service").isEmpty();
    }

    @Test
    void mixedTypeLevelAndMethodLevel_noDuplicateGeneration() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Item",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Item {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);
        // Service has BOTH type-level AND method-level @AgenticExposed
        JavaFileObject service = JavaFileObjects.forSourceString("test.MixedService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Mixed service", returnType = Item.class)
                public class MixedService {
                    @AgenticExposed(description = "Also annotated", returnType = Item.class)
                    public Item findOne() { return null; }
                    public Item findAnother() { return null; }
                }
                """);

        // Should NOT throw FilerException from duplicate file generation
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(entity, service);
        String toolSource = getGeneratedSource(compilation, "test.generated.MixedServiceMcpTool");
        // Type-level wins: both public methods included
        assertThat(toolSource).contains("name = \"findOne\"");
        assertThat(toolSource).contains("name = \"findAnother\"");
    }

    @Test
    void incompatibleReturnType_emitsError() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Item",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Item {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);
        // Method returns List<String> but returnType declares Item — incompatible cast
        JavaFileObject service = JavaFileObjects.forSourceString("test.BadService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                import java.util.List;
                public class BadService {
                    @AgenticExposed(description = "Bad cast", returnType = Item.class)
                    public List<String> getItems() { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(entity, service);
        CompilationSubject.assertThat(compilation).hadErrorContaining(
                "is not compatible with method return type");
    }

    @Test
    void compatibleReturnType_wildcardPassesValidation() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Item",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Item {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);
        // Method returns List<?> — raw/wildcard, validation is inconclusive, allow it
        JavaFileObject service = JavaFileObjects.forSourceString("test.WildcardService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                import java.util.List;
                public class WildcardService {
                    @AgenticExposed(description = "Wildcard ok", returnType = Item.class)
                    public List<?> getItems() { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(entity, service);
        // Should not emit error — wildcard is allowed
        var toolFile = compilation.generatedSourceFile("test.generated.WildcardServiceMcpTool");
        assertThat(toolFile).as("MCP tool should be generated for wildcard return").isPresent();
    }

    @Test
    void returnTypeUpcast_subToSuper_passes() {
        // List<Internal> + returnType=Base → OK (Internal is-a Base)
        JavaFileObject base = JavaFileObjects.forSourceString("test.Base",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Base {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);
        JavaFileObject internal = JavaFileObjects.forSourceString("test.Internal",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Internal extends Base {}
                """);
        JavaFileObject service = JavaFileObjects.forSourceString("test.UpcastService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                import java.util.List;
                public class UpcastService {
                    @AgenticExposed(description = "Upcast ok", returnType = Base.class)
                    public List<Internal> getItems() { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .compile(base, internal, service);
        var toolFile = compilation.generatedSourceFile("test.generated.UpcastServiceMcpTool");
        assertThat(toolFile).as("MCP tool should be generated for upcast return").isPresent();
    }

    @Test
    void returnTypeDowncast_superToSub_failsWithError() {
        // List<Base> + returnType=Internal → ERROR (would cast Base → Internal)
        JavaFileObject base = JavaFileObjects.forSourceString("test.Base",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Base {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);
        JavaFileObject internal = JavaFileObjects.forSourceString("test.Internal",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Internal extends Base {}
                """);
        JavaFileObject service = JavaFileObjects.forSourceString("test.DowncastService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                import java.util.List;
                public class DowncastService {
                    @AgenticExposed(description = "Downcast bad", returnType = Internal.class)
                    public List<Base> getItems() { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .compile(base, internal, service);
        CompilationSubject.assertThat(compilation).hadErrorContaining(
                "is not compatible with method return type");
    }

    @Test
    void returnTypeDowncast_arraySuperToSub_failsWithError() {
        // Base[] + returnType=Internal → ERROR (would cast Base → Internal)
        JavaFileObject base = JavaFileObjects.forSourceString("test.Base",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Base {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);
        JavaFileObject internal = JavaFileObjects.forSourceString("test.Internal",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgentVisibleClass public class Internal extends Base {}
                """);
        JavaFileObject service = JavaFileObjects.forSourceString("test.DowncastArrayService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class DowncastArrayService {
                    @AgenticExposed(description = "Downcast bad", returnType = Internal.class)
                    public Base[] getItems() { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .compile(base, internal, service);
        CompilationSubject.assertThat(compilation).hadErrorContaining(
                "is not compatible with method return type");
    }

    private static String getGeneratedSource(Compilation compilation, String qualifiedName) {
        // Generated code references Spring annotations not on test classpath,
        // so compilation may fail. The source files are still generated.
        var file = compilation.generatedSourceFile(qualifiedName);
        assertThat(file).as("Generated file: " + qualifiedName).isPresent();
        try {
            return file.get().getCharContent(true).toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
