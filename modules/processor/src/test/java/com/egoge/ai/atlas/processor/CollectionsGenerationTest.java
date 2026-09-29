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

        assertThatThrownBy(() -> classes.mcp("byStatus", "{\"status\": \"NEW\", \"size\": 0}"))
                .hasStackTraceContaining("Page size must not be less than one");
        assertThatThrownBy(() -> classes.mcp("byStatus", "{\"status\": \"NEW\", \"page\": -1, \"size\": 2}"))
                .hasStackTraceContaining("Page index must not be less than zero");
        // recent declares maxResults = 3, its page-size ceiling
        assertThatThrownBy(() -> classes.mcp("recent", "{\"size\": 4}"))
                .hasStackTraceContaining("recent accepts a page size of at most 3; got 4");
        assertThat(callback(classes, "recent").getToolDefinition().description())
                .contains("pass size (at least 1, at most 3)");
        MockHttpServletResponse tooLarge = rest(classes, "/recent", "size", "4");
        assertThat(tooLarge.getStatus()).isEqualTo(400);
        assertThat(tooLarge.getErrorMessage()).contains("recent accepts a page size of at most 3");
        assertThat(restJson(classes, "/recent", "size", "3").path("content").size()).isEqualTo(3);
    }

    // ================================================================ Pageable on REST

    @Test
    void springDataBindsThePageableOnRestAndAnAllowListedSortPassesThrough() throws Exception {
        Compilation compilation = compile(FLAG_ON);
        GeneratedClasses classes = new GeneratedClasses(compilation);

        assertThat(source(compilation, "shop.generated.OrderServiceRestController"))
                .contains("public PageResult<OrderDto> byStatus(@RequestParam String status, Pageable pageable)");
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
