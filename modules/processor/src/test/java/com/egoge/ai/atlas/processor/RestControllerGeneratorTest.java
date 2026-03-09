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
 * Tests for RestControllerGenerator. Generated controllers reference Spring Web
 * annotations which aren't on the test classpath, so we verify the generated
 * source content as strings.
 */
class RestControllerGeneratorTest {

    @Test
    void generatesRestControllerWithMappings() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);

        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(entity, service);

        String source = getGeneratedSource(compilation, "test.generated.OrderServiceRestController");
        assertThat(source).contains("@RestController");
        assertThat(source).contains("@RequestMapping(\"/api/v1/order-service\")");
        assertThat(source).contains("@PostMapping(\"/find-by-id\")");
        assertThat(source).contains("OrderDto.fromEntity(service.findById(id))");
    }

    @Test
    void generatesListReturnForCollectionMethods() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Product",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;
                @AgenticEntity
                public class Product {
                    @AgenticField(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);

        JavaFileObject service = JavaFileObjects.forSourceString("test.ProductService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                import java.util.List;
                @AgenticExposed(description = "Product ops", returnType = Product.class)
                public class ProductService {
                    public List<Product> listAll() { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(entity, service);

        String source = getGeneratedSource(compilation, "test.generated.ProductServiceRestController");
        assertThat(source).contains("List<ProductDto>");
        assertThat(source).contains("stream().map(e -> ProductDto.fromEntity((Product) e)).toList()");
    }

    @Test
    void channelFiltering_apiOnly_generatesRestEndpoint() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Item",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity public class Item {
                    @AgenticField(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);
        JavaFileObject service = JavaFileObjects.forSourceString("test.ApiService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class ApiService {
                    @AgenticExposed(description = "API only", returnType = Item.class,
                                    channels = { AgenticExposed.Channel.API })
                    public Item find() { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(entity, service);
        String source = getGeneratedSource(compilation, "test.generated.ApiServiceRestController");
        assertThat(source).contains("@RestController");
        assertThat(source).contains("@GetMapping(\"/find\")");
    }

    @Test
    void channelFiltering_aiOnly_skipsRestEndpoint() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Item",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity public class Item {
                    @AgenticField(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);
        JavaFileObject service = JavaFileObjects.forSourceString("test.AiOnlyService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class AiOnlyService {
                    @AgenticExposed(description = "AI only", returnType = Item.class,
                                    channels = { AgenticExposed.Channel.AI })
                    public Item find() { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(entity, service);
        var file = compilation.generatedSourceFile("test.generated.AiOnlyServiceRestController");
        assertThat(file).as("REST controller should NOT be generated for AI-only service").isEmpty();
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
