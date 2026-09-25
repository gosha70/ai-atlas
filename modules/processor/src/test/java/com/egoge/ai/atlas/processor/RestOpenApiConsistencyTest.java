/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden test: the generated REST controllers and the generated OpenAPI document describe the
 * same contract — same (HTTP method, path) set, query parameters, response content — and
 * ambiguous mappings are compile errors (FR-001..FR-007).
 */
class RestOpenApiConsistencyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CLASS_MAPPING = Pattern.compile("@RequestMapping\\(\"([^\"]+)\"\\)");
    private static final Pattern METHOD_MAPPING = Pattern.compile("@(Get|Post)Mapping\\(\"([^\"]+)\"\\)");
    private static final String BASE = "/api/v1/catalog-service";

    private static final JavaFileObject ITEM = JavaFileObjects.forSourceString("test.Item",
            """
            package test;
            import com.egoge.ai.atlas.annotations.*;
            @AgenticEntity public class Item {
                @AgenticField(description = "ID") private Long id;
                public Long getId() { return id; }
            }
            """);

    private static final JavaFileObject CATALOG_SERVICE = JavaFileObjects.forSourceString("test.CatalogService",
            """
            package test;
            import com.egoge.ai.atlas.annotations.AgenticExposed;
            import java.util.List;
            import java.util.Map;
            public class CatalogService {
                @AgenticExposed(description = "Current item", returnType = Item.class)
                public Item current() { return null; }
                @AgenticExposed(description = "Item by id", returnType = Item.class)
                public Item findById(Long id) { return null; }
                @AgenticExposed(description = "Search items", returnType = Item.class)
                public List<Item> search(String name, Integer limit, Boolean active) { return null; }
                @AgenticExposed(description = "Item label")
                public String label(Long id) { return null; }
                @AgenticExposed(description = "Item count")
                public long count() { return 0; }
                @AgenticExposed(description = "Item ids")
                public List<Long> ids() { return null; }
                @AgenticExposed(description = "Item stats")
                public Map<String, Object> stats() { return null; }
                @AgenticExposed(description = "Touch item")
                public void touch(Long id) { }
                @AgenticExposed(description = "Reset catalog", channels = { AgenticExposed.Channel.API })
                public void reset() { }
            }
            """);

    private static final JavaFileObject OVERLOADED_ORDER_SERVICE = JavaFileObjects.forSourceString(
            "test.OrderService",
            """
            package test;
            import com.egoge.ai.atlas.annotations.AgenticExposed;
            @AgenticExposed(description = "Orders", channels = { AgenticExposed.Channel.API })
            public class OrderService {
                public String find() { return null; }
                public String find(Long id) { return null; }
            }
            """);

    @Test
    void controllerMappingsMatchDocumentedOperations() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(ITEM, CATALOG_SERVICE);

        assertThat(compilation.status()).as(compilation.diagnostics().toString())
                .isEqualTo(Compilation.Status.SUCCESS);
        JsonNode doc = openApi(compilation);
        assertThat(controllerMappings(compilation)).isEqualTo(documentedOperations(doc));

        Map<String, JsonNode> ops = operationsByMapping(doc);
        assertThat(ops.keySet()).containsExactlyInAnyOrder(
                "GET " + BASE + "/current",
                "POST " + BASE + "/find-by-id",
                "POST " + BASE + "/search",
                "POST " + BASE + "/label",
                "GET " + BASE + "/count",
                "GET " + BASE + "/ids",
                "GET " + BASE + "/stats",
                "POST " + BASE + "/touch",
                "GET " + BASE + "/reset");

        ops.values().forEach(op -> {
            assertThat(op.has("requestBody")).as("requestBody on " + op).isFalse();
            assertThat(op.path("responses").has("200")).as("200 on " + op).isTrue();
        });

        // No arguments; DTO return
        JsonNode current = ops.get("GET " + BASE + "/current");
        assertThat(current.has("parameters")).isFalse();
        assertThat(jsonSchema(current).path("$ref").asText()).isEqualTo("#/components/schemas/ItemDto");

        // One scalar argument
        assertThat(queryParameters(ops.get("POST " + BASE + "/find-by-id")))
                .containsExactly("id:integer:int64");

        // Several arguments; collection return
        JsonNode search = ops.get("POST " + BASE + "/search");
        assertThat(queryParameters(search))
                .containsExactly("name:string:", "limit:integer:int32", "active:boolean:");
        assertThat(jsonSchema(search).path("type").asText()).isEqualTo("array");
        assertThat(jsonSchema(search).path("items").path("$ref").asText())
                .isEqualTo("#/components/schemas/ItemDto");

        // Non-DTO returns
        JsonNode label = ops.get("POST " + BASE + "/label").path("responses").path("200").path("content");
        assertThat(label.fieldNames()).toIterable().containsExactly("text/plain");
        assertThat(label.path("text/plain").path("schema").path("type").asText()).isEqualTo("string");
        assertThat(jsonSchema(ops.get("GET " + BASE + "/count")).path("format").asText()).isEqualTo("int64");
        JsonNode ids = jsonSchema(ops.get("GET " + BASE + "/ids"));
        assertThat(ids.path("type").asText()).isEqualTo("array");
        assertThat(ids.path("items").path("type").asText()).isEqualTo("integer");
        assertThat(jsonSchema(ops.get("GET " + BASE + "/stats")).path("type").asText()).isEqualTo("object");

        // void on the default channels and on the API channel: no response content
        assertThat(ops.get("POST " + BASE + "/touch").path("responses").path("200").has("content")).isFalse();
        assertThat(ops.get("GET " + BASE + "/reset").path("responses").path("200").has("content")).isFalse();
        String controller = generatedSource(compilation, "test/generated/CatalogServiceRestController.java");
        assertThat(controller).contains("public void touch(").contains("service.touch(id);")
                .contains("public void reset(").contains("service.reset();")
                .doesNotContain("return service.touch").doesNotContain("return service.reset");
        String tool = generatedSource(compilation, "test/generated/CatalogServiceMcpTool.java");
        assertThat(tool).contains("public void touch(").contains("service.touch(id);")
                .doesNotContain("return service.touch").doesNotContain("reset");
    }

    @Test
    void operationsSharingAPathAreMergedWithDistinctOperationIds() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(OVERLOADED_ORDER_SERVICE);

        assertThat(compilation.status()).as(compilation.diagnostics().toString())
                .isEqualTo(Compilation.Status.SUCCESS);
        JsonNode doc = openApi(compilation);
        assertThat(controllerMappings(compilation)).isEqualTo(documentedOperations(doc));

        JsonNode pathItem = doc.path("paths").path("/api/v1/order-service/find");
        assertThat(pathItem.fieldNames()).toIterable().containsExactlyInAnyOrder("get", "post");
        assertThat(pathItem.path("get").path("operationId").asText()).isEqualTo("OrderService_find_get");
        assertThat(pathItem.path("post").path("operationId").asText()).isEqualTo("OrderService_find_post");
        assertThat(queryParameters(pathItem.path("post"))).containsExactly("id:integer:int64");
    }

    @Test
    void qualifiedOperationIdSkipsIdsAlreadyTaken() {
        JavaFileObject legacy = JavaFileObjects.forSourceString("test.LegacyService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Legacy", channels = { AgenticExposed.Channel.API })
                public class LegacyService {
                    public String OrderService_find_get() { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .compile(OVERLOADED_ORDER_SERVICE, legacy);

        assertThat(compilation.status()).as(compilation.diagnostics().toString())
                .isEqualTo(Compilation.Status.SUCCESS);
        JsonNode doc = openApi(compilation);
        assertThat(controllerMappings(compilation)).isEqualTo(documentedOperations(doc));

        Map<String, JsonNode> ops = operationsByMapping(doc);
        assertThat(ops.get("GET /api/v1/legacy-service/order-service_find_get").path("operationId").asText())
                .isEqualTo("OrderService_find_get");
        assertThat(ops.get("GET /api/v1/order-service/find").path("operationId").asText())
                .isEqualTo("OrderService_find_get_2");
        assertThat(ops.get("POST /api/v1/order-service/find").path("operationId").asText())
                .isEqualTo("OrderService_find_post");
        List<String> operationIds = ops.values().stream().map(op -> op.path("operationId").asText()).toList();
        assertThat(operationIds).doesNotHaveDuplicates();
    }

    @Test
    void overloadsOnOnePostPathAreACompileErrorAtEachSite() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Orders", channels = { AgenticExposed.Channel.API })
                public class OrderService {
                    public String find(Long id) { return null; }
                    public String find(String code) { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(service);

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        List<Diagnostic<? extends JavaFileObject>> errors = duplicateMappingErrors(compilation);
        assertThat(errors).hasSize(2);
        assertThat(errors).extracting(Diagnostic::getLineNumber).containsExactlyInAnyOrder(5L, 6L);
        assertThat(errors).allSatisfy(error -> assertThat(error.getMessage(null))
                .contains("POST /api/v1/order-service/find")
                .contains("also mapped by test.OrderService#find"));
    }

    @Test
    void sameNamedServicesInDifferentPackagesAreACompileErrorAtEachSite() {
        JavaFileObject first = JavaFileObjects.forSourceString("a.OrderService",
                """
                package a;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Orders A", channels = { AgenticExposed.Channel.API })
                public class OrderService {
                    public String find(Long id) { return null; }
                }
                """);
        JavaFileObject second = JavaFileObjects.forSourceString("b.OrderService",
                """
                package b;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Orders B", channels = { AgenticExposed.Channel.API })
                public class OrderService {
                    public String find(Long id) { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(first, second);

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        List<Diagnostic<? extends JavaFileObject>> errors = duplicateMappingErrors(compilation);
        assertThat(errors).hasSize(2);
        assertThat(errors).extracting(error -> error.getSource().getName())
                .anySatisfy(name -> assertThat(name).contains("a/OrderService"))
                .anySatisfy(name -> assertThat(name).contains("b/OrderService"));
        assertThat(errors).allSatisfy(error -> assertThat(error.getMessage(null))
                .contains("POST /api/v1/order-service/find"));
        assertThat(errors).anySatisfy(error -> assertThat(error.getMessage(null))
                .contains("of a.OrderService#find is also mapped by b.OrderService#find"));
        assertThat(errors).anySatisfy(error -> assertThat(error.getMessage(null))
                .contains("of b.OrderService#find is also mapped by a.OrderService#find"));
    }

    // --- helpers ---

    private static List<Diagnostic<? extends JavaFileObject>> duplicateMappingErrors(Compilation compilation) {
        return compilation.errors().stream()
                .filter(error -> error.getMessage(null).contains("REST mapping"))
                .<Diagnostic<? extends JavaFileObject>>map(error -> error)
                .toList();
    }

    /** "HTTP-method path" of every mapping in every generated REST controller. */
    private static Set<String> controllerMappings(Compilation compilation) {
        Set<String> mappings = new TreeSet<>();
        for (JavaFileObject file : compilation.generatedSourceFiles()) {
            if (!file.getName().endsWith("RestController.java")) {
                continue;
            }
            String source = charContent(file);
            Matcher classMapping = CLASS_MAPPING.matcher(source);
            assertThat(classMapping.find()).as("class-level @RequestMapping in " + file.getName()).isTrue();
            Matcher methodMapping = METHOD_MAPPING.matcher(source);
            while (methodMapping.find()) {
                mappings.add(methodMapping.group(1).toUpperCase() + " " + classMapping.group(1)
                        + methodMapping.group(2));
            }
        }
        return mappings;
    }

    private static Set<String> documentedOperations(JsonNode doc) {
        return new TreeSet<>(operationsByMapping(doc).keySet());
    }

    private static Map<String, JsonNode> operationsByMapping(JsonNode doc) {
        Map<String, JsonNode> ops = new TreeMap<>();
        doc.path("paths").properties().forEach(path -> path.getValue().properties().forEach(
                op -> ops.put(op.getKey().toUpperCase() + " " + path.getKey(), op.getValue())));
        return ops;
    }

    /** "name:type:format" of each parameter, asserting every one is a required query parameter. */
    private static List<String> queryParameters(JsonNode operation) {
        List<String> params = new ArrayList<>();
        for (JsonNode param : operation.path("parameters")) {
            assertThat(param.path("in").asText()).isEqualTo("query");
            assertThat(param.path("required").asBoolean()).isTrue();
            params.add(param.path("name").asText() + ":" + param.path("schema").path("type").asText()
                    + ":" + param.path("schema").path("format").asText());
        }
        return params;
    }

    private static JsonNode jsonSchema(JsonNode operation) {
        JsonNode content = operation.path("responses").path("200").path("content");
        assertThat(content.fieldNames()).toIterable().containsExactly("application/json");
        return content.path("application/json").path("schema");
    }

    private static JsonNode openApi(Compilation compilation) {
        var file = compilation.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/openapi/openapi-v1.json");
        assertThat(file).as("OpenAPI spec resource file").isPresent();
        try {
            return JSON.readTree(charContent(file.get()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String generatedSource(Compilation compilation, String path) {
        var file = compilation.generatedFile(StandardLocation.SOURCE_OUTPUT, path);
        assertThat(file).as(path).isPresent();
        return charContent(file.get());
    }

    private static String charContent(JavaFileObject file) {
        try {
            return file.getCharContent(true).toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
