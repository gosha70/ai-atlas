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
