/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.annotations.AgenticBound;
import com.egoge.ai.atlas.processor.ChannelProjectionFixtures.GeneratedClasses;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletResponse;

import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.JSON;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.fieldNames;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.method;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.openApi;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.source;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.CONSTRAINTS;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.FLAG_ON;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.PROJECTIONS;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.callback;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.compile;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.pageRequested;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.resource;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.rest;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.restJson;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.tool;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@code ai.atlas.collections=true} generates, proved by calling the generated classes: MCP
 * through Spring AI's {@code MethodToolCallbackProvider}, REST through MockMvc with Spring Data's
 * {@code Pageable} resolver. Nothing is synthesised, truncated or clamped.
 */
class CollectionsGenerationTest {

    private static final String BY_STATUS = "/api/v1/order-service/by-status";
    private static final String APPLICATION_JSON = "application/json";

    private static GeneratedClasses classes(String... options) {
        Compilation compilation = compile(options);
        assertThat(compilation).succeeded();
        return new GeneratedClasses(compilation);
    }

    private static JsonNode responseSchema(JsonNode operation) {
        return operation.path("responses").path("200").path("content").path(APPLICATION_JSON).path("schema");
    }

    // ================================================================ Pageable on MCP

    @Test
    void aPageableBecomesPageAndSizeOnMcpAndAPageKeepsItsMetadata() throws Exception {
        GeneratedClasses classes = classes(FLAG_ON);

        ToolCallback tool = callback(classes, "byStatus");
        JsonNode schema = JSON.readTree(tool.getToolDefinition().inputSchema());
        assertThat(fieldNames(schema.path("properties"))).containsExactly("status", "page", "size");
        assertThat(schema.path("required").toString()).contains("\"size\"").doesNotContain("\"page\"");
        assertThat(tool.getToolDefinition().description()).isEqualTo("Orders in a status, a page at a time."
                + " Results are paged: pass size (at least 1) and optionally page (zero-based, default 0); the result"
                + " carries hasNext, totalElements and totalPages.");

        JsonNode page = classes.mcp("byStatus", "{\"status\": \"NEW\", \"page\": 1, \"size\": 2}");
        assertThat(fieldNames(page)).containsExactly("content", "number", "size", "hasNext", "totalElements",
                "totalPages");
        assertThat(page.path("content").findValuesAsText("id")).containsExactly("3", "4");
        assertThat(page.path("number").asInt()).isEqualTo(1);
        assertThat(page.path("size").asInt()).isEqualTo(2);
        assertThat(page.path("hasNext").asBoolean()).isTrue();
        assertThat(page.path("totalElements").asLong()).isEqualTo(5);
        assertThat(page.path("totalPages").asInt()).isEqualTo(3);
        // Never a sort: the MCP tool builds an unsorted request from page and size
        assertThat(pageRequested(classes)).isEqualTo(PageRequest.of(1, 2));

        // page is optional and defaults to the first page
        assertThat(classes.mcp("byStatus", "{\"status\": \"NEW\", \"size\": 2}").path("number").asInt()).isZero();
    }

    @Test
    void aSliceCarriesHasNextButNoTotals() throws Exception {
        GeneratedClasses classes = classes(FLAG_ON);

        JsonNode slice = classes.mcp("recent", "{\"page\": 1, \"size\": 3}");
        assertThat(fieldNames(slice)).containsExactly("content", "number", "size", "hasNext");
        assertThat(slice.path("content").findValuesAsText("id")).containsExactly("4", "5");
        assertThat(slice.path("hasNext").asBoolean()).isFalse();
    }

    @Test
    void aPageableWithAListReturnIsBoundedBySizeWithNoEnvelope() throws Exception {
        GeneratedClasses classes = classes(FLAG_ON);

        JsonNode newest = classes.mcp("newest", "{\"size\": 2}");
        assertThat(newest.isArray()).isTrue();
        assertThat(newest.findValuesAsText("id")).containsExactly("1", "2");
        assertThat(restJson(classes, "/newest", "page", "2", "size", "2").findValuesAsText("id")).containsExactly("5");
    }

    @Test
    void anOutOfRangePageInputIsRejectedNotClamped() throws Exception {
        GeneratedClasses classes = classes(FLAG_ON);

        // MCP: each input is named with its range; recent declares maxResults = 3, its page-size ceiling
        assertThatThrownBy(() -> classes.mcp("recent", "{\"size\": 4}"))
                .hasStackTraceContaining("[ai-atlas] recent: size must be an integer from 1 to 3; got 4");
        assertThatThrownBy(() -> classes.mcp("recent", "{\"size\": 0}"))
                .hasStackTraceContaining("recent: size must be an integer from 1 to 3; got 0");
        assertThatThrownBy(() -> classes.mcp("recent", "{\"size\": -5}"))
                .hasStackTraceContaining("recent: size must be an integer from 1 to 3; got -5");
        assertThatThrownBy(() -> classes.mcp("recent", "{}"))
                .hasStackTraceContaining("recent: size is required: an integer from 1 to 3");
        assertThatThrownBy(() -> classes.mcp("recent", "{\"page\": -1, \"size\": 2}"))
                .hasStackTraceContaining("recent: page must be an integer from 0 to 2147483647; got -1");
        // Without a ceiling, any int size: a larger one is rejected by the tool, never overflowed
        assertThatThrownBy(() -> classes.mcp("newest", "{\"size\": 99999999999}"))
                .hasStackTraceContaining("newest: size must be an integer from 1 to 2147483647; got 99999999999");
        assertThatThrownBy(() -> classes.mcp("newest", "{\"size\": 2, \"page\": 99999999999}"))
                .hasStackTraceContaining("newest: page must be an integer from 0 to 2147483647; got 99999999999");
        assertThat(callback(classes, "recent").getToolDefinition().description())
                .contains("pass size (at least 1, at most 3)");

        // REST: the raw inputs are checked, as Spring Data's resolver would clamp or replace each of these
        assertRejected(classes, "recent: size must be an integer from 1 to 3; got 4", "/recent", "size", "4");
        assertRejected(classes, "recent: size must be an integer from 1 to 3; got 0", "/recent", "size", "0");
        assertRejected(classes, "newest: size must be an integer from 1 to 2147483647; got -5", "/newest",
                "size", "-5");
        assertRejected(classes, "newest: size must be an integer from 1 to 2147483647; got 'abc'", "/newest",
                "size", "abc");
        assertRejected(classes, "newest: size must be an integer from 1 to 2147483647; got '99999999999'",
                "/newest", "size", "99999999999");
        // Above Spring Data's maximum page size (2000): with no ceiling, and under a ceiling above it
        assertRejected(classes, "newest: size 5000 is above the application's maximum page size 2000", "/newest",
                "size", "5000");
        assertRejected(classes, "bulk: size 3000 is above the application's maximum page size 2000", "/bulk",
                "size", "3000");
        assertRejected(classes, "newest: page must be an integer from 0 to 2147483647; got -3", "/newest",
                "page", "-3", "size", "2");
        assertRejected(classes, "newest: page must be an integer from 0 to 2147483647; got 'x'", "/newest",
                "page", "x", "size", "2");
        // A missing size takes the application's default page size (20), unless it is above the ceiling
        assertRejected(classes, "recent: size is required here: the application's default page size 20"
                + " (spring.data.web.pageable.default-page-size) is above this operation's maximum of 3; pass size"
                + " as an integer from 1 to 3", "/recent");
        assertThat(restJson(classes, "/newest").size()).isEqualTo(5);
        assertThat(pageRequested(classes)).isEqualTo(PageRequest.of(0, 20));
        // In range, the request is passed as given
        assertThat(restJson(classes, "/recent", "size", "3").path("content").size()).isEqualTo(3);
        assertThat(restJson(classes, "/bulk", "page", "0", "size", "2000").size()).isEqualTo(5);
        assertThat(pageRequested(classes)).isEqualTo(PageRequest.of(0, 2000));
    }

    private static void assertRejected(GeneratedClasses classes, String message, String path, String... params)
            throws Exception {
        MockHttpServletResponse response = rest(classes, path, params);
        assertThat(response.getStatus()).as(path + " " + String.join(" ", params)).isEqualTo(400);
        assertThat(response.getErrorMessage()).isEqualTo("[ai-atlas] " + message
                + (message.contains("maximum page size 2000") ? " (spring.data.web.pageable.max-page-size); pass a"
                + " size from 1 to 2000" : ""));
    }

    // ================================================================ Pageable on REST

    @Test
    void springDataBindsThePageableOnRestAndAnAllowListedSortPassesThrough() throws Exception {
        Compilation compilation = compile(FLAG_ON);
        GeneratedClasses classes = new GeneratedClasses(compilation);

        assertThat(source(compilation, "shop.generated.OrderServiceRestController"))
                .contains("public PageResult<OrderDto> byStatus(@RequestParam String status, Pageable pageable,\n"
                        + "            WebRequest request)");
        JsonNode page = restJson(classes, "/by-status", "status", "NEW", "page", "1", "size", "2", "sort", "id,desc");
        assertThat(page.path("content").findValuesAsText("id")).containsExactly("3", "4");
        assertThat(page.path("totalElements").asLong()).isEqualTo(5);
        assertThat(page.path("totalPages").asInt()).isEqualTo(3);
        Pageable received = pageRequested(classes);
        assertThat(received.getPageNumber()).isEqualTo(1);
        assertThat(received.getPageSize()).isEqualTo(2);
        assertThat(received.getSort().toString()).isEqualTo("id: DESC");
    }

    @Test
    void aSortOnAPropertyThatIsNotAllowListedIsRejected() throws Exception {
        GeneratedClasses classes = classes(FLAG_ON);

        MockHttpServletResponse response = rest(classes, "/by-status", "status", "NEW", "size", "2",
                "sort", "creditScore,desc");
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getErrorMessage()).contains("byStatus cannot be sorted by 'creditScore'; sortable: [id,"
                + " status]");
    }

    @Test
    void withoutAnAllowListTheSortIsDropped() throws Exception {
        GeneratedClasses classes = classes(FLAG_ON);

        restJson(classes, "/recent", "size", "2", "sort", "creditScore,desc");
        assertThat(pageRequested(classes).getSort().isSorted()).isFalse();
        assertThat(pageRequested(classes)).isEqualTo(PageRequest.of(0, 2));
    }

    // ================================================================ OpenAPI and mcp-tools.json

    @Test
    void openApiDescribesPageSizeAndAnAllowListedSortWithNoDefaultsAndTheEnvelope() throws Exception {
        JsonNode paths = openApi(compile(FLAG_ON)).path("paths");

        JsonNode byStatus = paths.path(BY_STATUS).path("post");
        assertThat(byStatus.path("parameters").findValuesAsText("name")).containsExactly("status", "page", "size",
                "sort");
        for (JsonNode parameter : byStatus.path("parameters")) {
            assertThat(parameter.has("default") || parameter.path("schema").has("default")).isFalse();
            if (!"status".equals(parameter.path("name").asText())) {
                assertThat(parameter.path("required").asBoolean()).isFalse();
            }
        }
        JsonNode envelope = responseSchema(byStatus);
        assertThat(fieldNames(envelope.path("properties")))
                .containsExactly("content", "number", "size", "hasNext", "totalElements", "totalPages");
        assertThat(envelope.path("required").size()).isEqualTo(6);
        assertThat(envelope.path("properties").path("content").path("items").path("$ref").asText())
                .isEqualTo("#/components/schemas/OrderDto");

        // Without an allow-list there is no sort; the ceiling is the size's maximum
        JsonNode recent = paths.path("/api/v1/order-service/recent").path("post");
        assertThat(recent.path("parameters").findValuesAsText("name")).containsExactly("page", "size");
        assertThat(recent.path("parameters").get(1).path("schema").path("maximum").asInt()).isEqualTo(3);
        assertThat(recent.path("parameters").get(1).path("schema").path("minimum").asInt()).isEqualTo(1);
        assertThat(fieldNames(responseSchema(recent).path("properties")))
                .containsExactly("content", "number", "size", "hasNext");

        // A declared bound is maxItems; a CURSOR is optional
        assertThat(responseSchema(paths.path("/api/v1/order-service/top").path("get")).path("maxItems").asInt())
                .isEqualTo(2);
        JsonNode search = paths.path("/api/v1/order-service/search").path("post").path("parameters");
        assertThat(search.get(2).path("name").asText()).isEqualTo("after");
        assertThat(search.get(2).path("required").asBoolean()).isFalse();
        // No 'pageable' parameter anywhere
        assertThat(paths.findValuesAsText("name")).doesNotContain("pageable");
    }

    @Test
    void mcpToolsJsonCarriesTheSameInputsAsTheToolClass() throws Exception {
        Compilation compilation = compile(FLAG_ON, CONSTRAINTS);
        assertThat(compilation).succeeded();
        JsonNode tools = JSON.readTree(resource(compilation, "META-INF/ai-atlas/mcp-tools.json"));
        GeneratedClasses classes = new GeneratedClasses(compilation);

        for (String name : new String[] {"byStatus", "recent", "search"}) {
            JsonNode declared = tool(tools, name).path("inputSchema");
            JsonNode derived = JSON.readTree(callback(classes, name).getToolDefinition().inputSchema());
            assertThat(fieldNames(declared.path("properties"))).as(name)
                    .isEqualTo(fieldNames(derived.path("properties")));
            assertThat(declared.path("required")).as(name).isEqualTo(derived.path("required"));
        }
        JsonNode recent = tool(tools, "recent").path("inputSchema").path("properties");
        assertThat(recent.path("page").path("minimum").asInt()).isZero();
        assertThat(recent.path("size").path("minimum").asInt()).isEqualTo(1);
        assertThat(recent.path("size").path("maximum").asInt()).isEqualTo(3);
        assertThat(tool(tools, "search").path("inputSchema").path("required").toString())
                .contains("\"limit\"").doesNotContain("\"after\"");
        assertThat(tool(tools, "search").path("inputSchema").path("properties").path("limit").path("maximum").asInt())
                .isEqualTo(3);
        // With constraints on, the tool class enforces its checks and calls go through
        assertThat(classes.mcp("recent", "{\"size\": 2}").path("content").size()).isEqualTo(2);
    }

    // ================================================================ LIMIT, CURSOR, declared bounds

    @Test
    void aLimitTheServiceHonoursIsRecognisedAndNothingIsAdded() throws Exception {
        for (String[] options : new String[][] {{FLAG_ON}, {FLAG_ON, CONSTRAINTS}}) {
            GeneratedClasses classes = classes(options);

            ToolCallback tool = callback(classes, "search");
            assertThat(fieldNames(JSON.readTree(tool.getToolDefinition().inputSchema()).path("properties")))
                    .containsExactly("text", "limit", "after");
            assertThat(tool.getToolDefinition().description()).isEqualTo("Search orders. Returns at most 'limit'"
                    + " results; pass 'after' to continue after a previous call.");
            // The cursor is optional: the first call has none
            assertThat(classes.mcp("search", "{\"text\": \"x\", \"limit\": 2}").findValuesAsText("id"))
                    .containsExactly("1", "2");
            assertThat(restJson(classes, "/search", "text", "x", "limit", "2").size()).isEqualTo(2);
        }
    }

    @Test
    void aDeclaredBoundIsPublishedButNeverEnforcedByTruncation() throws Exception {
        Compilation compilation = compile(FLAG_ON);
        GeneratedClasses classes = new GeneratedClasses(compilation);

        assertThat(callback(classes, "top").getToolDefinition().description())
                .isEqualTo("The top orders. Returns at most 2 results.");
        // The service breaks its declared bound: both wrappers pass all five through, unchanged
        assertThat(classes.mcp("top", "{}").size()).isEqualTo(5);
        assertThat(JSON.valueToTree(classes.rest("top")).size()).isEqualTo(5);
        // ...and carry the bound for the runtime's WARN
        assertThat(method(classes.load("shop.generated.OrderServiceMcpTool"), "top")
                .getAnnotation(AgenticBound.class).maxResults()).isEqualTo(2);
        assertThat(method(classes.load("shop.generated.OrderServiceRestController"), "top")
                .getAnnotation(AgenticBound.class).maxResults()).isEqualTo(2);
        assertThat(method(classes.load("shop.generated.OrderServiceMcpTool"), "list")
                .getAnnotation(AgenticBound.class)).isNull();
    }

    @Test
    void anOptionalOfACollectionCarriesItsBoundOnEverySurface() throws Exception {
        Compilation compilation = CollectionsFixtures.compileWithOrder("shop.Maybe", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.*;
                @AgenticExposed(description = "Maybe")
                public class Maybe {
                    @AgenticExposed(description = "Maybe names", maxResults = 2)
                    public Optional<List<String>> names() { return Optional.of(List.of("a", "b", "c")); }
                    @AgenticExposed(description = "Maybe orders", maxResults = 2)
                    public Optional<Order[]> orders() { return Optional.empty(); }
                    @AgenticExposed(description = "Maybe counts", maxResults = 2)
                    public Optional<Map<String, Long>> counts() { return Optional.empty(); }
                }
                """, FLAG_ON);
        assertThat(compilation).succeeded();
        GeneratedClasses classes = new GeneratedClasses(compilation);

        JsonNode paths = openApi(compilation).path("paths");
        JsonNode names = responseSchema(paths.path("/api/v1/maybe/names").path("get"));
        assertThat(names.path("type").asText()).isEqualTo("array");
        assertThat(names.path("items").path("type").asText()).isEqualTo("string");
        assertThat(names.path("maxItems").asInt()).isEqualTo(2);
        assertThat(responseSchema(paths.path("/api/v1/maybe/orders").path("get")).path("maxItems").asInt())
                .isEqualTo(2);
        assertThat(responseSchema(paths.path("/api/v1/maybe/counts").path("get")).path("maxProperties").asInt())
                .isEqualTo(2);
        for (String wrapper : new String[] {"shop.generated.MaybeMcpTool", "shop.generated.MaybeRestController"}) {
            assertThat(method(classes.load(wrapper), "names").getAnnotation(AgenticBound.class).maxResults())
                    .as(wrapper).isEqualTo(2);
        }
        assertThat(CollectionsFixtures.messages(compilation, javax.tools.Diagnostic.Kind.WARNING))
                .noneMatch(m -> m.contains(CollectionsFixtures.NO_PAGING));
    }

    @Test
    void aPageWithoutAPageableKeepsItsMetadataInTheEnvelope() throws Exception {
        GeneratedClasses classes = classes(FLAG_ON);

        JsonNode archived = classes.mcp("archived", "{}");
        assertThat(archived.path("content").size()).isEqualTo(5);
        assertThat(archived.path("totalElements").asLong()).isEqualTo(5);
        JsonNode rest = JSON.valueToTree(classes.rest("archived"));
        assertThat(rest.path("hasNext").asBoolean()).isFalse();
        assertThat(rest.path("totalPages").asInt()).isEqualTo(1);
    }

    // ================================================================ Phase 4

    @Test
    void theEnvelopeWrapsEachChannelsProjection() throws Exception {
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions(FLAG_ON, PROJECTIONS, CollectionsFixtures.PARAMETERS)
                .compile(JavaFileObjects.forSourceString("shop.Order",
                                CollectionsFixtures.ORDER_SRC.formatted(", channels = Channel.API")),
                        JavaFileObjects.forSourceString("shop.OrderService", CollectionsFixtures.SERVICE_SRC));
        assertThat(compilation).succeeded();
        GeneratedClasses classes = new GeneratedClasses(compilation);

        assertThat(method(classes.load("shop.generated.OrderServiceMcpTool"), "byStatus").getGenericReturnType()
                .getTypeName()).isEqualTo("shop.generated.OrderServiceMcpTool$PageResult<shop.generated.OrderAiDto>");
        JsonNode mcp = classes.mcp("byStatus", "{\"status\": \"NEW\", \"size\": 2}");
        assertThat(fieldNames(mcp.path("content").get(0))).containsExactly("id", "status");
        JsonNode rest = restJson(classes, "/by-status", "status", "NEW", "size", "2");
        assertThat(rest.path("content").get(0).path("marginCents").asInt()).isEqualTo(99);
    }
}
