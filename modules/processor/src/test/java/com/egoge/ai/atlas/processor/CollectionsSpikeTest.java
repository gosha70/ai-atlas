/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.ChannelProjectionFixtures.GeneratedClasses;
import com.egoge.ai.atlas.processor.contract.ContractGate;
import com.egoge.ai.atlas.processor.contract.IrJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.JSON;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.fieldNames;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.method;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.openApi;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.source;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * <strong>Spike (Phase 5, epic #23 &sect;8).</strong> Characterises how collection returns are
 * exposed today, and proves the {@code ai.atlas.collections} prototype: a Spring Data paging
 * contract is represented accurately on REST, OpenAPI and MCP; a limit the service honours and a
 * declared bound are accepted; anything else is a WARNING, an ERROR under strict; nothing is
 * synthesised or truncated.
 *
 * <p>The generated classes are loaded and called: MCP through Spring AI's own
 * {@link MethodToolCallbackProvider}, REST through MockMvc with Spring Data's
 * {@link PageableHandlerMethodArgumentResolver}, as Spring Boot registers it.
 */
class CollectionsSpikeTest {

    private static final String FLAG_ON = "-Aai.atlas.collections=true";
    private static final String STRICT = "-Aai.atlas.strict=true";
    private static final String CONSTRAINTS = "-Aai.atlas.constraints=true";
    private static final String PARAMETERS = "-parameters";
    private static final String NO_PAGING = "no paging contract";

    static final String ORDER_SRC = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import java.util.List;
            import java.util.stream.LongStream;
            @AgenticEntity(description = "A customer order")
            public class Order {
                @AgenticField(description = "Order id") private Long id;
                @AgenticField(description = "Status") private String status;
                public Order(Long id, String status) { this.id = id; this.status = status; }
                public Long getId() { return id; }
                public String getStatus() { return status; }
                /** Five orders: every result set of the fixture. */
                public static List<Order> all() {
                    return LongStream.rangeClosed(1, 5).mapToObj(i -> new Order(i, "NEW")).toList();
                }
            }
            """;
    /** {@code %s} is the declaration on {@code top}: a bound, or nothing. */
    static final String SERVICE_SRC = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import com.egoge.ai.atlas.annotations.AgenticParam.Paging;
            import org.springframework.data.domain.*;
            import java.util.List;
            @AgenticExposed(description = "Order operations", returnType = Order.class)
            public class OrderService {
                /** The last Pageable the service received, to prove what the wrappers pass. */
                public static Pageable lastPageable;
                @AgenticExposed(description = "Orders in a status, a page at a time")
                public Page<Order> byStatus(String status, Pageable pageable) {
                    lastPageable = pageable;
                    List<Order> all = Order.all();
                    int from = (int) Math.min(pageable.getOffset(), all.size());
                    int to = Math.min(from + pageable.getPageSize(), all.size());
                    return new PageImpl<>(all.subList(from, to), pageable, all.size());
                }
                @AgenticExposed(description = "Recent orders, a slice at a time")
                public Slice<Order> recent(Pageable pageable) {
                    lastPageable = pageable;
                    List<Order> all = Order.all();
                    int from = (int) Math.min(pageable.getOffset(), all.size());
                    int to = Math.min(from + pageable.getPageSize(), all.size());
                    return new SliceImpl<>(all.subList(from, to), pageable, to < all.size());
                }
                @AgenticExposed(description = "Every order")
                public List<Order> list() { return Order.all(); }
                @AgenticExposed(description = "The top orders"%s)
                public List<Order> top() { return Order.all(); }
                @AgenticExposed(description = "Search orders")
                public List<Order> search(String text, @AgenticParam(paging = Paging.LIMIT) int limit,
                                          @AgenticParam(paging = Paging.CURSOR) String after) {
                    return Order.all().subList(0, Math.min(limit, 5));
                }
                @AgenticExposed(description = "Orders after a cursor")
                public List<Order> after(@AgenticParam(paging = Paging.CURSOR) String cursor) { return Order.all(); }
                @AgenticExposed(description = "One order")
                public Order find(Long id) { return Order.all().get(0); }
            }
            """;
    private static final String BOUND = ", maxResults = 2";

    private static List<JavaFileObject> shop(String topDeclaration) {
        return List.of(JavaFileObjects.forSourceString("shop.Order", ORDER_SRC),
                JavaFileObjects.forSourceString("shop.OrderService", SERVICE_SRC.formatted(topDeclaration)));
    }

    private static Compilation compile(String topDeclaration, String... options) {
        return javac().withProcessors(new AgenticProcessor()).withOptions((Object[]) options)
                .compile(shop(topDeclaration));
    }

    // ================================================================ today (option off)

    @Test
    void todayAPageableToolCannotBeCalledOverMcp() throws Exception {
        Compilation compilation = compile(BOUND, PARAMETERS);
        assertThat(compilation).succeeded();
        GeneratedClasses classes = new GeneratedClasses(compilation);

        // The tool keeps the Pageable parameter; Spring AI describes the interface as an object...
        String inputSchema = callback(classes, "byStatus").getToolDefinition().inputSchema();
        assertThat(JSON.readTree(inputSchema).path("properties").has("pageable")).isTrue();
        // ...but cannot build one from the arguments a model sends
        assertThatThrownBy(() -> classes.mcp("byStatus",
                "{\"status\": \"NEW\", \"pageable\": {\"pageNumber\": 1, \"pageSize\": 2}}"))
                .hasStackTraceContaining("Pageable");
    }

    @Test
    void todayAPageableEndpointCannotBeBoundOverRest() throws Exception {
        Compilation compilation = compile(BOUND, PARAMETERS);
        GeneratedClasses classes = new GeneratedClasses(compilation);

        // @RequestParam wins over Spring Data's resolver, so the endpoint wants a 'pageable' string
        assertThat(source(compilation, "shop.generated.OrderServiceRestController"))
                .contains("@RequestParam Pageable pageable");
        MockHttpServletResponse response = rest(classes, "/by-status", "status", "NEW", "page", "1", "size", "2");
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getErrorMessage()).contains("pageable");
    }

    @Test
    void todayAPageIsFlattenedToAListAndItsMetadataIsLost() throws Exception {
        Compilation compilation = compile(BOUND, PARAMETERS);
        GeneratedClasses classes = new GeneratedClasses(compilation);

        // The Page is iterated as an Iterable: no totals, no page number, no hasNext
        assertThat(method(classes.load("shop.generated.OrderServiceMcpTool"), "recent").getGenericReturnType()
                .getTypeName()).isEqualTo("java.util.List<shop.generated.OrderDto>");
        JsonNode byStatus = openApi(compilation).path("paths").path("/api/v1/order-service/by-status").path("post");
        assertThat(byStatus.path("responses").path("200").path("content").path("application/json")
                .path("schema").path("type").asText()).isEqualTo("array");
        // ...and OpenAPI publishes a required 'pageable' string no client can send
        assertThat(byStatus.path("parameters").findValuesAsText("name")).containsExactly("status", "pageable");
    }

    @Test
    void todayAnUnboundedCollectionIsSilentlyAccepted() {
        Compilation compilation = compile("", PARAMETERS, STRICT);

        assertThat(compilation).succeeded();
        assertThat(messages(compilation, Diagnostic.Kind.WARNING)).noneMatch(m -> m.contains(NO_PAGING));
    }

    @Test
    void withTheOptionOffADeclaredBoundOrPagingRoleIsAWarningThatItIsIgnored() {
        Compilation compilation = compile(BOUND, PARAMETERS);

        assertThat(compilation).succeeded();
        assertThat(messages(compilation, Diagnostic.Kind.WARNING)).filteredOn(m -> m.contains("is ignored"))
                .hasSize(3); // top (maxResults), search (LIMIT and CURSOR), after (CURSOR)
    }

    // ================================================================ prototype: Spring Data paging

    @Test
    void aPageableAndPageAreRepresentedOnMcp() throws Exception {
        Compilation compilation = compile("", FLAG_ON, PARAMETERS);
        assertThat(compilation).succeeded();
        GeneratedClasses classes = new GeneratedClasses(compilation);

        ToolCallback tool = callback(classes, "byStatus");
        JsonNode schema = JSON.readTree(tool.getToolDefinition().inputSchema());
        assertThat(fieldNames(schema.path("properties"))).containsExactly("status", "page", "size");
        assertThat(schema.path("required").toString()).contains("size").doesNotContain("page\"");
        assertThat(tool.getToolDefinition().description()).contains("Results are paged", "totalElements");

        JsonNode page = classes.mcp("byStatus", "{\"status\": \"NEW\", \"page\": 1, \"size\": 2}");
        assertThat(page.path("content").findValuesAsText("id")).containsExactly("3", "4");
        assertThat(page.path("number").asInt()).isEqualTo(1);
        assertThat(page.path("size").asInt()).isEqualTo(2);
        assertThat(page.path("hasNext").asBoolean()).isTrue();
        assertThat(page.path("totalElements").asLong()).isEqualTo(5);
        assertThat(page.path("totalPages").asInt()).isEqualTo(3);
        assertThat(pageRequested(classes)).isEqualTo(org.springframework.data.domain.PageRequest.of(1, 2));

        // page is optional and defaults to the first page
        assertThat(classes.mcp("byStatus", "{\"status\": \"NEW\", \"size\": 2}").path("number").asInt()).isZero();
    }

    @Test
    void anInvalidPageSizeIsRejectedNotClamped() throws Exception {
        GeneratedClasses classes = new GeneratedClasses(compile("", FLAG_ON, PARAMETERS));

        assertThatThrownBy(() -> classes.mcp("byStatus", "{\"status\": \"NEW\", \"size\": 0}"))
                .hasStackTraceContaining("Page size must not be less than one");
    }

    @Test
    void aSliceCarriesHasNextButNoTotals() throws Exception {
        GeneratedClasses classes = new GeneratedClasses(compile("", FLAG_ON, PARAMETERS));

        JsonNode slice = classes.mcp("recent", "{\"page\": 2, \"size\": 2}");
        assertThat(fieldNames(slice)).containsExactly("content", "number", "size", "hasNext");
        assertThat(slice.path("content").findValuesAsText("id")).containsExactly("5");
        assertThat(slice.path("hasNext").asBoolean()).isFalse();
    }

    @Test
    void aPageableAndPageAreRepresentedOnRest() throws Exception {
        Compilation compilation = compile("", FLAG_ON, PARAMETERS);
        GeneratedClasses classes = new GeneratedClasses(compilation);

        MockHttpServletResponse response = rest(classes, "/by-status", "status", "NEW", "page", "1", "size", "2",
                "sort", "id,desc");
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode page = JSON.readTree(response.getContentAsString());
        assertThat(page.path("content").findValuesAsText("id")).containsExactly("3", "4");
        assertThat(page.path("totalElements").asLong()).isEqualTo(5);
        // Spring Data's resolver bound every input, sort included
        Pageable received = pageRequested(classes);
        assertThat(received.getPageNumber()).isEqualTo(1);
        assertThat(received.getPageSize()).isEqualTo(2);
        assertThat(received.getSort().toString()).isEqualTo("id: DESC");
    }

    @Test
    void aPageableAndPageAreRepresentedInOpenApi() throws Exception {
        JsonNode byStatus = openApi(compile("", FLAG_ON, PARAMETERS)).path("paths")
                .path("/api/v1/order-service/by-status").path("post");

        assertThat(byStatus.path("parameters").findValuesAsText("name")).containsExactly("status", "page", "size", "sort");
        for (JsonNode parameter : byStatus.path("parameters")) {
            if (!"status".equals(parameter.path("name").asText())) {
                assertThat(parameter.path("required").asBoolean()).isFalse();
            }
        }
        JsonNode schema = byStatus.path("responses").path("200").path("content").path("application/json").path("schema");
        assertThat(fieldNames(schema.path("properties")))
                .containsExactly("content", "number", "size", "hasNext", "totalElements", "totalPages");
        assertThat(schema.path("properties").path("content").path("items").path("$ref").asText())
                .isEqualTo("#/components/schemas/OrderDto");
        assertThat(schema.path("properties").has("maxItems")).isFalse();
    }

    @Test
    void mcpToolsJsonAgreesWithTheToolClass() throws Exception {
        Compilation compilation = compile("", FLAG_ON, CONSTRAINTS, PARAMETERS);
        assertThat(compilation).succeeded();

        JsonNode tools = JSON.readTree(compilation.generatedFile(StandardLocation.CLASS_OUTPUT,
                "META-INF/ai-atlas/mcp-tools.json").orElseThrow().getCharContent(true).toString());
        JsonNode byStatus = StreamSupport.stream(tools.path("tools").spliterator(), false)
                .filter(t -> "byStatus".equals(t.path("name").asText())).findFirst().orElseThrow();
        JsonNode schema = byStatus.path("inputSchema");
        assertThat(fieldNames(schema.path("properties"))).containsExactly("status", "page", "size");
        assertThat(schema.path("required").toString()).contains("size");
        assertThat(schema.path("properties").path("size").path("minimum").asInt()).isEqualTo(1);
    }

    @Test
    void theMcpEnvelopeCarriesTheAiProjection() throws Exception {
        String order = ORDER_SRC
                .replace("import java.util.List;", "import java.util.List;\n"
                        + "import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;")
                .replace("public Order(Long id, String status) {",
                        "@AgenticField(description = \"Internal margin\", channels = Channel.API)"
                                + " private Integer marginCents = 99;\n"
                                + "    public Integer getMarginCents() { return marginCents; }\n"
                                + "    public Order(Long id, String status) {");
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions(FLAG_ON, "-Aai.atlas.projections=true", PARAMETERS)
                .compile(JavaFileObjects.forSourceString("shop.Order", order),
                        JavaFileObjects.forSourceString("shop.OrderService", SERVICE_SRC.formatted("")));
        assertThat(compilation).succeeded();
        GeneratedClasses classes = new GeneratedClasses(compilation);

        assertThat(method(classes.load("shop.generated.OrderServiceMcpTool"), "byStatus").getGenericReturnType()
                .getTypeName()).endsWith("PageResult<shop.generated.OrderAiDto>");
        JsonNode mcp = classes.mcp("byStatus", "{\"status\": \"NEW\", \"size\": 2}");
        assertThat(mcp.path("content").get(0).has("marginCents")).isFalse();
        JsonNode rest = JSON.readTree(rest(classes, "/by-status", "status", "NEW", "size", "2").getContentAsString());
        assertThat(rest.path("content").get(0).path("marginCents").asInt()).isEqualTo(99);
    }

    // ================================================================ prototype: limit, bound, unbounded

    @Test
    void aLimitTheServiceHonoursIsAPagingContractAndNothingIsAdded() throws Exception {
        Compilation compilation = compile("", FLAG_ON, PARAMETERS);
        GeneratedClasses classes = new GeneratedClasses(compilation);

        assertThat(messages(compilation, Diagnostic.Kind.WARNING)).noneMatch(m -> m.contains("#search"));
        ToolCallback tool = callback(classes, "search");
        assertThat(fieldNames(JSON.readTree(tool.getToolDefinition().inputSchema()).path("properties")))
                .containsExactly("text", "limit", "after");
        assertThat(tool.getToolDefinition().description())
                .contains("Returns at most 'limit' results; pass 'after' to continue");
        assertThat(classes.mcp("search", "{\"text\": \"x\", \"limit\": 2, \"after\": \"\"}").size()).isEqualTo(2);
    }

    @Test
    void aDeclaredBoundIsPublishedButNeverEnforcedByTruncation() throws Exception {
        Compilation compilation = compile(BOUND, FLAG_ON, PARAMETERS);
        GeneratedClasses classes = new GeneratedClasses(compilation);

        assertThat(messages(compilation, Diagnostic.Kind.WARNING)).noneMatch(m -> m.contains("#top"));
        JsonNode top = openApi(compilation).path("paths").path("/api/v1/order-service/top").path("get");
        assertThat(top.path("responses").path("200").path("content").path("application/json").path("schema")
                .path("maxItems").asInt()).isEqualTo(2);
        assertThat(callback(classes, "top").getToolDefinition().description())
                .isEqualTo("The top orders. Returns at most 2 results.");
        // The service breaks its declared bound: the wrappers pass all five through, unchanged
        assertThat(classes.mcp("top", "{}").size()).isEqualTo(5);
        assertThat(JSON.valueToTree(classes.rest("top")).size()).isEqualTo(5);
    }

    @Test
    void anUnboundedCollectionIsAWarning() {
        Compilation compilation = compile("", FLAG_ON, PARAMETERS);

        assertThat(compilation).succeeded();
        assertThat(messages(compilation, Diagnostic.Kind.WARNING)).filteredOn(m -> m.contains(NO_PAGING))
                .anyMatch(m -> m.contains("shop.OrderService#list returns java.util.List<shop.Order>"
                        + " on channels [AI, API]"))
                .anyMatch(m -> m.contains("#top"))
                .anyMatch(m -> m.contains("#after") && m.contains("CURSOR parameter bounds nothing"))
                .noneMatch(m -> m.contains("#byStatus") || m.contains("#recent") || m.contains("#search")
                        || m.contains("#find"));
    }

    @Test
    void strictModeMakesAnUnboundedCollectionAnError() {
        Compilation compilation = compile("", FLAG_ON, PARAMETERS, STRICT);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("shop.OrderService#list returns");
        assertThat(messages(compilation, Diagnostic.Kind.ERROR)).filteredOn(m -> m.contains(NO_PAGING)).hasSize(3);
    }

    @Test
    void streamAndMapReturnsAreCollectionsToo() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions(FLAG_ON, PARAMETERS)
                .compile(JavaFileObjects.forSourceString("shop.Feed", """
                        package shop;
                        import com.egoge.ai.atlas.annotations.*;
                        import java.util.Map;
                        import java.util.stream.Stream;
import java.util.stream.StreamSupport;
                        @AgenticExposed(description = "Feeds")
                        public class Feed {
                            @AgenticExposed(description = "Every event")
                            public Stream<String> events() { return Stream.of("a"); }
                            @AgenticExposed(description = "Counts by key")
                            public Map<String, Long> counts() { return Map.of(); }
                        }
                        """));

        assertThat(messages(compilation, Diagnostic.Kind.WARNING)).filteredOn(m -> m.contains(NO_PAGING))
                .anyMatch(m -> m.contains("#events")).anyMatch(m -> m.contains("#counts"));
    }

    @Test
    void misusedDeclarationsAreErrors() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions(FLAG_ON, PARAMETERS)
                .compile(JavaFileObjects.forSourceString("shop.Order", ORDER_SRC),
                        JavaFileObjects.forSourceString("shop.Bad", """
                        package shop;
                        import com.egoge.ai.atlas.annotations.*;
                        import com.egoge.ai.atlas.annotations.AgenticParam.Paging;
                        import org.springframework.data.domain.*;
                        import java.util.List;
                        @AgenticExposed(description = "Bad", returnType = Order.class, maxResults = 5)
                        public class Bad {
                            @AgenticExposed(description = "single", maxResults = 5)
                            public Order single() { return null; }
                            @AgenticExposed(description = "zero", maxResults = 0)
                            public List<Order> zero() { return null; }
                            @AgenticExposed(description = "text limit")
                            public List<Order> textLimit(@AgenticParam(paging = Paging.LIMIT) String n) { return null; }
                            @AgenticExposed(description = "paged and bounded", maxResults = 5)
                            public Page<Order> pagedAndBounded(Pageable p) { return null; }
                            @AgenticExposed(description = "two pageables")
                            public Page<Order> twice(Pageable a, Pageable b) { return null; }
                            @AgenticExposed(description = "shadowed")
                            public Page<Order> shadowed(Pageable p, int size) { return null; }
                        }
                        """));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@AgenticExposed(maxResults) on shop.Bad must be declared on each method");
        assertThat(compilation).hadErrorContaining("shop.Bad#single declares maxResults or a paging role, but returns shop.Order");
        assertThat(compilation).hadErrorContaining("shop.Bad#zero declares maxResults = 0");
        assertThat(compilation).hadErrorContaining("a limit must be an int, long or short");
        assertThat(compilation).hadErrorContaining("shop.Bad#pagedAndBounded is paged and declares maxResults");
        assertThat(compilation).hadErrorContaining("shop.Bad#twice takes more than one Pageable");
        assertThat(compilation).hadErrorContaining("parameter named 'size'");
    }

    // ================================================================ the Contract IR and the gate

    @Test
    void theContractIrAndGateDoNotSeeABoundAppearOrDisappear() throws Exception {
        String bounded = ir(compile(BOUND, FLAG_ON, PARAMETERS));
        String unbounded = ir(compile("", FLAG_ON, PARAMETERS));

        assertThat(unbounded).isEqualTo(bounded);
        assertThat(ContractGate.compare(IrJson.parse(bounded, "baseline"), IrJson.parse(unbounded, "fresh")))
                .isEmpty();
        assertThat(ContractGate.documentDifferences(IrJson.parse(bounded, "baseline"),
                IrJson.parse(unbounded, "fresh"))).isEmpty();
    }

    @Test
    void theGateSeesAListBecomingAPageAsABreakingChange() throws Exception {
        String before = ir(compile("", PARAMETERS));
        String after = ir(javac().withProcessors(new AgenticProcessor()).withOptions(PARAMETERS)
                .compile(JavaFileObjects.forSourceString("shop.Order", ORDER_SRC),
                        JavaFileObjects.forSourceString("shop.OrderService", SERVICE_SRC.formatted("")
                                .replace("public List<Order> list() { return Order.all(); }",
                                        "public Page<Order> list(Pageable p) { return Page.empty(); }"))));

        List<ContractGate.Difference> differences = ContractGate.compare(IrJson.parse(before, "baseline"),
                IrJson.parse(after, "fresh"));
        // The Pageable is part of the operation's identity: the old operation is removed, a new one added
        assertThat(differences).anyMatch(d -> d.breaking() && d.path().contains("list()"));
        assertThat(differences).anyMatch(d -> d.path().contains("list(org.springframework.data.domain.Pageable)"));
    }

    // ================================================================ helpers

    private static String ir(Compilation compilation) throws IOException {
        assertThat(compilation).succeeded();
        return compilation.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/ai-atlas/api.ir.json")
                .orElseThrow().getCharContent(true).toString();
    }

    private static List<String> messages(Compilation compilation, Diagnostic.Kind kind) {
        return compilation.diagnostics().stream().filter(d -> d.getKind() == kind)
                .map(d -> d.getMessage(null)).toList();
    }

    private static ToolCallback callback(GeneratedClasses classes, String name) throws ReflectiveOperationException {
        Class<?> service = classes.load("shop.OrderService");
        Object tool = classes.load("shop.generated.OrderServiceMcpTool").getConstructor(service)
                .newInstance(service.getConstructor().newInstance());
        return Stream.of(MethodToolCallbackProvider.builder().toolObjects(tool).build().getToolCallbacks())
                .filter(c -> c.getToolDefinition().name().equals(name)).findFirst().orElseThrow();
    }

    private static Pageable pageRequested(GeneratedClasses classes) throws ReflectiveOperationException {
        return (Pageable) classes.load("shop.OrderService").getField("lastPageable").get(null);
    }

    /** POSTs to the generated controller through Spring MVC, with Spring Data's Pageable resolver registered. */
    private static MockHttpServletResponse rest(GeneratedClasses classes, String path, String... params)
            throws Exception {
        Class<?> service = classes.load("shop.OrderService");
        Object controller = classes.load("shop.generated.OrderServiceRestController").getConstructor(service)
                .newInstance(service.getConstructor().newInstance());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
        var request = post("/api/v1/order-service" + path);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request).andReturn().getResponse();
    }
}
