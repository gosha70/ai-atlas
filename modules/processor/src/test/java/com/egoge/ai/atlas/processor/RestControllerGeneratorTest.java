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
                import com.egoge.ai.atlas.annotations.AgentVisible;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Order {
                    @AgentVisible(description = "ID") private Long id;
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
                import com.egoge.ai.atlas.annotations.AgentVisible;
                import com.egoge.ai.atlas.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Product {
                    @AgentVisible(description = "ID") private Long id;
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
