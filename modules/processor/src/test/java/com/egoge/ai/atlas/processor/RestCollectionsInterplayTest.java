/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.RestTestSupport.GeneratedClasses;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.ContractIr.Bound;
import com.egoge.ai.atlas.processor.contract.IrJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static com.egoge.ai.atlas.processor.RestTestSupport.CUSTOMER;
import static com.egoge.ai.atlas.processor.RestTestSupport.CUSTOMER_SERVICE;
import static com.egoge.ai.atlas.processor.RestTestSupport.JSON;
import static com.egoge.ai.atlas.processor.RestTestSupport.ORDER;
import static com.egoge.ai.atlas.processor.RestTestSupport.ORDER_SERVICE;
import static com.egoge.ai.atlas.processor.RestTestSupport.REST_ON;
import static com.egoge.ai.atlas.processor.RestTestSupport.compile;
import static com.egoge.ai.atlas.processor.RestTestSupport.compileUnchecked;
import static com.egoge.ai.atlas.processor.RestTestSupport.errors;
import static com.egoge.ai.atlas.processor.RestTestSupport.openApi;
import static com.egoge.ai.atlas.processor.RestTestSupport.outputs;
import static com.egoge.ai.atlas.processor.RestTestSupport.resource;
import static com.egoge.ai.atlas.processor.RestTestSupport.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * {@code ai.atlas.rest} and {@code ai.atlas.collections} together: an operation keeps its explicit
 * REST mapping (method, path, status, path variables) and its paging contract (raw {@code page}
 * and {@code size} checks, the envelope, the ceiling, the bound) on the controller, in OpenAPI and
 * in the Contract IR; a paging input is a query parameter only; and each flag on its own still
 * generates what it did alone.
 */
class RestCollectionsInterplayTest {

    private static final String COLLECTIONS_ON = "-Aai.atlas.collections=true";
    private static final List<String> BOTH = List.of(REST_ON, COLLECTIONS_ON);
    private static final String ORDER_SRC = CollectionsFixtures.ORDER_SRC.formatted("");
    private static final String ORDERS_PATH = "/api/v1/order-service/orders/{status}";

    /** A paged GET with a path variable, a LIMIT search answering 202, and a declared bound answering 203. */
    private static final String SERVICE = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import com.egoge.ai.atlas.annotations.AgenticExposed.HttpMethod;
            import com.egoge.ai.atlas.annotations.AgenticExposed.Rest;
            import com.egoge.ai.atlas.annotations.AgenticParam.In;
            import jakarta.validation.constraints.Max;
            import org.springframework.data.domain.*;
            import java.util.List;
            @AgenticExposed(description = "Order operations", returnType = Order.class)
            public class OrderService {
                public static Pageable lastPageable;
                public static String lastStatus;
                @AgenticExposed(description = "Orders in a status, a page at a time", maxResults = 50,
                        rest = @Rest(method = HttpMethod.GET, path = "/orders/{status}"))
                public Page<Order> byStatus(@AgenticParam(in = In.PATH) String status, Pageable pageable) {
                    lastStatus = status;
                    lastPageable = pageable;
                    List<Order> all = Order.all();
                    int from = (int) Math.min(pageable.getOffset(), all.size());
                    return new PageImpl<>(all.subList(from, Math.min(from + pageable.getPageSize(), all.size())),
                            pageable, all.size());
                }
                @AgenticExposed(description = "Search orders in a status",
                        rest = @Rest(method = HttpMethod.POST, path = "/{status}/search", status = 202))
                public List<Order> search(String status, @AgenticParam(paging = Paging.LIMIT) @Max(3) int limit) {
                    lastStatus = status;
                    return Order.all().subList(0, limit);
                }
                @AgenticExposed(description = "The top orders in a status", maxResults = 2,
                        rest = @Rest(method = HttpMethod.GET, path = "/top/{status}", status = 203))
                public List<Order> top(String status) { return Order.all().subList(0, 2); }
            }
            """;

    private static MockMvc mvc(GeneratedClasses classes) throws Exception {
        Class<?> service = classes.load("shop.OrderService");
        Object controller = classes.load("shop.generated.OrderServiceRestController").getConstructor(service)
                .newInstance(service.getConstructor().newInstance());
        return MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
    }

    private static MockHttpServletResponse call(MockMvc mvc, MockHttpServletRequestBuilder request, String... params)
            throws Exception {
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private static Object field(GeneratedClasses classes, String name) throws ReflectiveOperationException {
        return classes.load("shop.OrderService").getField(name).get(null);
    }

    // ================================================================ one operation, both contracts

    @Test
    void aPathVariableAndAPageableAreBoundTogetherAndOutOfRangePagingIsRejected() throws Exception {
        Compilation compilation = compile(BOTH, ORDER_SRC, SERVICE);

        String controller = source(compilation, "shop.generated.OrderServiceRestController");
        assertThat(controller).contains("@GetMapping(\"/orders/{status}\")")
                .contains("@GetMapping(\"/top/{status}\")\n    @ResponseStatus(HttpStatus.NON_AUTHORITATIVE_INFORMATION)\n"
                        + "    @AgenticBound(\n            maxResults = 2\n    )\n"
                        + "    public List<OrderDto> top(@PathVariable(\"status\") String status)")
                .contains("public PageResult<OrderDto> byStatus(@PathVariable(\"status\") String status, Pageable pageable,\n"
                        + "            WebRequest request)");

        GeneratedClasses classes = new GeneratedClasses(compilation);
        MockMvc mvc = mvc(classes);
        MockHttpServletResponse ok = call(mvc, get(ORDERS_PATH, "SHIPPED"), "page", "1", "size", "2");
        assertThat(ok.getStatus()).as(ok.getErrorMessage()).isEqualTo(200);
        JsonNode page = JSON.readTree(ok.getContentAsString());
        assertThat(page.path("content").findValuesAsText("id")).containsExactly("3", "4");
        assertThat(page.path("number").asInt()).isEqualTo(1);
        assertThat(page.path("size").asInt()).isEqualTo(2);
        assertThat(page.path("totalElements").asLong()).isEqualTo(5);
        assertThat(page.path("totalPages").asInt()).isEqualTo(3);
        assertThat(field(classes, "lastStatus")).isEqualTo("SHIPPED");
        assertThat(field(classes, "lastPageable")).isEqualTo(PageRequest.of(1, 2));
        // The ceiling itself is accepted
        assertThat(call(mvc, get(ORDERS_PATH, "NEW"), "size", "50").getStatus()).isEqualTo(200);
        assertThat(((Pageable) field(classes, "lastPageable")).getPageSize()).isEqualTo(50);

        MockHttpServletResponse zero = call(mvc, get(ORDERS_PATH, "NEW"), "size", "0");
        assertThat(zero.getStatus()).isEqualTo(400);
        assertThat(zero.getErrorMessage()).isEqualTo("[ai-atlas] byStatus: size must be an integer from 1 to 50;"
                + " got 0");
        MockHttpServletResponse above = call(mvc, get(ORDERS_PATH, "NEW"), "size", "51");
        assertThat(above.getStatus()).isEqualTo(400);
        assertThat(above.getErrorMessage()).isEqualTo("[ai-atlas] byStatus: size must be an integer from 1 to 50;"
                + " got 51");
        MockHttpServletResponse negative = call(mvc, get(ORDERS_PATH, "NEW"), "page", "-1", "size", "2");
        assertThat(negative.getStatus()).isEqualTo(400);
        assertThat(negative.getErrorMessage()).isEqualTo("[ai-atlas] byStatus: page must be an integer from 0 to"
                + " 2147483647; got -1");

        // The LIMIT operation keeps its 202 and path variable, its limit in the query
        MockHttpServletResponse search = call(mvc, post("/api/v1/order-service/{status}/search", "OPEN"),
                "limit", "2");
        assertThat(search.getStatus()).isEqualTo(202);
        assertThat(JSON.readTree(search.getContentAsString()).findValuesAsText("id")).containsExactly("1", "2");
        assertThat(field(classes, "lastStatus")).isEqualTo("OPEN");

        MockHttpServletResponse top = call(mvc, get("/api/v1/order-service/top/{status}", "NEW"));
        assertThat(top.getStatus()).isEqualTo(203);
        assertThat(JSON.readTree(top.getContentAsString()).size()).isEqualTo(2);
    }

    @Test
    void openApiPublishesThePathParameterAndTheBoundedPagingParameters() throws Exception {
        Compilation compilation = compile(BOTH, ORDER_SRC, SERVICE);
        RestTestSupport.assertValidOpenApi(compilation);
        JsonNode doc = openApi(compilation);

        JsonNode byStatus = doc.path("paths").path(ORDERS_PATH).path("get");
        assertThat(byStatus.isMissingNode()).as(doc.path("paths").toString()).isFalse();
        JsonNode parameters = byStatus.path("parameters");
        assertThat(parameters.findValuesAsText("name")).containsExactly("status", "page", "size");
        JsonNode status = parameters.get(0);
        assertThat(status.path("in").asText()).isEqualTo("path");
        assertThat(status.path("required").asBoolean()).isTrue();
        JsonNode page = parameters.get(1);
        assertThat(page.path("in").asText()).isEqualTo("query");
        assertThat(page.path("required").asBoolean()).isFalse();
        assertThat(page.path("schema").path("minimum").asInt()).isZero();
        JsonNode size = parameters.get(2);
        assertThat(size.path("in").asText()).isEqualTo("query");
        assertThat(size.path("schema").path("minimum").asInt()).isEqualTo(1);
        assertThat(size.path("schema").path("maximum").asInt()).isEqualTo(50);
        // No sort is published: none is allow-listed
        assertThat(parameters.findValuesAsText("name")).doesNotContain("sort");
        JsonNode envelope = byStatus.path("responses").path("200").path("content").path("application/json")
                .path("schema");
        assertThat(envelope.path("properties").has("content")).as(envelope.toString()).isTrue();
        assertThat(envelope.path("properties").has("totalElements")).isTrue();

        JsonNode search = doc.path("paths").path("/api/v1/order-service/{status}/search").path("post");
        assertThat(search.path("responses").has("202")).as(search.toString()).isTrue();
        assertThat(search.path("parameters").findValuesAsText("in")).containsExactly("path", "query");

        JsonNode top = doc.path("paths").path("/api/v1/order-service/top/{status}").path("get");
        assertThat(top.path("responses").path("203").path("content").path("application/json").path("schema")
                .path("maxItems").asInt()).as(top.toString()).isEqualTo(2);
    }

    @Test
    void theIrRecordsTheBoundAndTheRestStatusAndLocationsOfOneOperation() throws Exception {
        ContractIr ir = IrJson.parse(resource(compile(BOTH, ORDER_SRC, SERVICE), ContractIr.RESOURCE_PATH), "test");

        ContractIr.Operation byStatus = operation(ir, "byStatus");
        assertThat(byStatus.rest().httpMethod()).isEqualTo("GET");
        assertThat(byStatus.rest().path()).isEqualTo("/order-service/orders/{status}");
        assertThat(byStatus.rest().status()).isEqualTo(200);
        assertThat(byStatus.rest().parameterIn()).containsExactly("PATH", "QUERY");
        assertThat(byStatus.returns().bound()).isEqualTo(new Bound("PAGEABLE", "PAGE", "pageable", null, 50));

        ContractIr.Operation search = operation(ir, "search");
        assertThat(search.rest().status()).isEqualTo(202);
        assertThat(search.rest().parameterIn()).containsExactly("PATH", "QUERY");
        assertThat(search.returns().bound()).isEqualTo(new Bound("LIMIT", "NONE", "limit", null, null));

        ContractIr.Operation top = operation(ir, "top");
        assertThat(top.rest().status()).isEqualTo(203);
        assertThat(top.rest().parameterIn()).containsExactly("PATH");
        assertThat(top.returns().bound()).isEqualTo(new Bound("DECLARED", "NONE", null, null, 2));
    }

    private static ContractIr.Operation operation(ContractIr ir, String method) {
        return ir.operations().stream().filter(o -> o.method().equals(method)).findFirst().orElseThrow();
    }

    // ================================================================ paging inputs are query-only

    private static Compilation compileMember(String member) {
        String service = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.HttpMethod;
                import com.egoge.ai.atlas.annotations.AgenticExposed.Rest;
                import com.egoge.ai.atlas.annotations.AgenticParam.In;
                import org.springframework.data.domain.*;
                import java.util.List;
                @AgenticExposed(description = "Orders", returnType = Order.class)
                public class BadService {
                    %s
                }
                """.formatted(member);
        return compileUnchecked(BOTH, ORDER_SRC, service);
    }

    @Test
    void aPagingInputBoundFromThePathOrTheBodyIsACompileError() {
        String query = "a paging input is a query parameter only";
        assertThat(errors(compileMember("""
                @AgenticExposed(description = "x", maxResults = 5, rest = @Rest(method = HttpMethod.POST))
                public List<Order> paged(@AgenticParam(in = In.BODY) Pageable pageable) { return null; }""")))
                .anySatisfy(e -> assertThat(e).contains("shop.BadService#paged binds the Pageable 'pageable' from"
                        + " the request body; " + query));
        assertThat(errors(compileMember("""
                @AgenticExposed(description = "x", rest = @Rest(method = HttpMethod.GET, path = "/top/{limit}"))
                public List<Order> top(@AgenticParam(in = In.PATH, paging = Paging.LIMIT) int limit) { return null; }""")))
                .anySatisfy(e -> assertThat(e).contains("shop.BadService#top binds the LIMIT 'limit' from the request"
                        + " path; " + query + ". Remove its @AgenticParam(in) or the {limit} path variable"));
        // A {var} alone places the parameter in the path
        assertThat(errors(compileMember("""
                @AgenticExposed(description = "x", rest = @Rest(method = HttpMethod.GET, path = "/top/{limit}"))
                public List<Order> top(@AgenticParam(paging = Paging.LIMIT) int limit) { return null; }""")))
                .anySatisfy(e -> assertThat(e).contains("binds the LIMIT 'limit' from the request path; " + query));
        assertThat(errors(compileMember("""
                @AgenticExposed(description = "x", rest = @Rest(method = HttpMethod.POST))
                public List<Order> next(@AgenticParam(paging = Paging.LIMIT) int limit,
                                        @AgenticParam(in = In.BODY, paging = Paging.CURSOR) Long after) {
                    return null;
                }""")))
                .anySatisfy(e -> assertThat(e).contains("shop.BadService#next binds the CURSOR 'after' from the"
                        + " request body; " + query));
        // A Pageable in the path is not a scalar path parameter either
        assertThat(errors(compileMember("""
                @AgenticExposed(description = "x", maxResults = 5, rest = @Rest(method = HttpMethod.GET, path = "/{pageable}"))
                public List<Order> paged(@AgenticParam(in = In.PATH) Pageable pageable) { return null; }""")))
                .isNotEmpty();
    }

    @Test
    void pagingInputsInTheQueryCompileCleanly() {
        Compilation compilation = compileMember("""
                @AgenticExposed(description = "x", rest = @Rest(method = HttpMethod.GET, path = "/top"))
                public List<Order> top(@AgenticParam(in = In.QUERY, paging = Paging.LIMIT) int limit,
                                       @AgenticParam(paging = Paging.CURSOR) Long after) { return null; }""");
        assertThat(errors(compilation)).isEmpty();
    }

    // ================================================================ each flag on its own

    @Test
    void theRestFlagWithNothingDeclaredChangesNoCollectionsOutput() {
        Compilation collectionsOnly = CollectionsFixtures.compile(COLLECTIONS_ON);
        Compilation both = CollectionsFixtures.compile(COLLECTIONS_ON, REST_ON);
        assertThat(collectionsOnly.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(both.status()).isEqualTo(Compilation.Status.SUCCESS);

        assertThat(outputs(both, false)).isEqualTo(outputs(collectionsOnly, false));
    }

    @Test
    void theCollectionsFlagChangesNoRestOutputWithoutPaging() {
        Compilation restOnly = compile(List.of(REST_ON), ORDER, ORDER_SERVICE, CUSTOMER, CUSTOMER_SERVICE);
        Compilation both = compile(BOTH, ORDER, ORDER_SERVICE, CUSTOMER, CUSTOMER_SERVICE);

        assertThat(outputs(both, false)).isEqualTo(outputs(restOnly, false));
    }
}
