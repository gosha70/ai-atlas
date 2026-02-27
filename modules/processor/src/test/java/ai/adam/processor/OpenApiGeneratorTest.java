/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for OpenApiGenerator. Verifies that the processor produces a valid
 * OpenAPI 3.0 JSON spec as a resource file in META-INF/openapi/openapi.json.
 */
class OpenApiGeneratorTest {

    @Test
    void generatesOpenApiSpecWithPathsAndSchemas() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import ai.adam.annotations.AgentVisible;
                import ai.adam.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Order {
                    @AgentVisible(description = "Order ID") private Long id;
                    @AgentVisible(description = "Status") private String status;
                    public Long getId() { return id; }
                    public String getStatus() { return status; }
                }
                """);

        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import ai.adam.annotations.AgenticExposed;
                @AgenticExposed(description = "Order operations", returnType = Order.class)
                public class OrderService {
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(entity, service);

        String json = getGeneratedResource(compilation);

        // OpenAPI version and info
        assertThat(json).contains("\"openapi\" : \"3.0.3\"");
        assertThat(json).contains("\"title\" : \"AI-ADAM Generated API\"");

        // Schema for OrderDto
        assertThat(json).contains("\"OrderDto\"");
        assertThat(json).contains("\"Order ID\"");
        assertThat(json).contains("\"int64\"");

        // Path for service method
        assertThat(json).contains("/api/v1/order-service/find-by-id");
        assertThat(json).contains("\"operationId\" : \"findById\"");
        assertThat(json).contains("#/components/schemas/OrderDto");
    }

    @Test
    void generatesArraySchemaForCollectionReturn() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Product",
                """
                package test;
                import ai.adam.annotations.AgentVisible;
                import ai.adam.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Product {
                    @AgentVisible(description = "ID") private Long id;
                    @AgentVisible(description = "Name") private String name;
                    public Long getId() { return id; }
                    public String getName() { return name; }
                }
                """);

        JavaFileObject service = JavaFileObjects.forSourceString("test.ProductService",
                """
                package test;
                import ai.adam.annotations.AgenticExposed;
                import java.util.List;
                @AgenticExposed(description = "Product ops", returnType = Product.class)
                public class ProductService {
                    public List<Product> listAll() { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(entity, service);

        String json = getGeneratedResource(compilation);

        // Array schema for list return
        assertThat(json).contains("\"type\" : \"array\"");
        assertThat(json).contains("#/components/schemas/ProductDto");
    }

    @Test
    void usesGetForNoParamsPostForParams() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Item",
                """
                package test;
                import ai.adam.annotations.AgentVisible;
                import ai.adam.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Item {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);

        JavaFileObject service = JavaFileObjects.forSourceString("test.ItemService",
                """
                package test;
                import ai.adam.annotations.AgenticExposed;
                import java.util.List;
                @AgenticExposed(description = "Item ops", returnType = Item.class)
                public class ItemService {
                    public List<Item> findAll() { return null; }
                    public Item findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(entity, service);

        String json = getGeneratedResource(compilation);

        // findAll (no params) → GET
        assertThat(json).contains("\"get\"");
        // findById (has params) → POST with requestBody
        assertThat(json).contains("\"post\"");
        assertThat(json).contains("\"requestBody\"");
    }

    @Test
    void includesMultipleServicesInSameSpec() {
        JavaFileObject entity1 = JavaFileObjects.forSourceString("test.Cat",
                """
                package test;
                import ai.adam.annotations.AgentVisible;
                import ai.adam.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Cat {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);

        JavaFileObject entity2 = JavaFileObjects.forSourceString("test.Dog",
                """
                package test;
                import ai.adam.annotations.AgentVisible;
                import ai.adam.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Dog {
                    @AgentVisible(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);

        JavaFileObject service1 = JavaFileObjects.forSourceString("test.CatService",
                """
                package test;
                import ai.adam.annotations.AgenticExposed;
                @AgenticExposed(description = "Cat ops", returnType = Cat.class)
                public class CatService {
                    public Cat findById(Long id) { return null; }
                }
                """);

        JavaFileObject service2 = JavaFileObjects.forSourceString("test.DogService",
                """
                package test;
                import ai.adam.annotations.AgenticExposed;
                @AgenticExposed(description = "Dog ops", returnType = Dog.class)
                public class DogService {
                    public Dog findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(entity1, entity2, service1, service2);

        String json = getGeneratedResource(compilation);

        // Both schemas present
        assertThat(json).contains("\"CatDto\"");
        assertThat(json).contains("\"DogDto\"");

        // Both service paths present
        assertThat(json).contains("/api/v1/cat-service/find-by-id");
        assertThat(json).contains("/api/v1/dog-service/find-by-id");
    }

    private static String getGeneratedResource(Compilation compilation) {
        var file = compilation.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/openapi/openapi.json");
        assertThat(file).as("OpenAPI spec resource file").isPresent();
        try {
            return file.get().getCharContent(true).toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
