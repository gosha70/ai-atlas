/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.RestTestSupport.GeneratedClasses;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.egoge.ai.atlas.processor.RestTestSupport.CUSTOMER;
import static com.egoge.ai.atlas.processor.RestTestSupport.CUSTOMER_SERVICE;
import static com.egoge.ai.atlas.processor.RestTestSupport.ORDER;
import static com.egoge.ai.atlas.processor.RestTestSupport.ORDER_SERVICE;
import static com.egoge.ai.atlas.processor.RestTestSupport.REST_ON;
import static com.egoge.ai.atlas.processor.RestTestSupport.assertValidOpenApi;
import static com.egoge.ai.atlas.processor.RestTestSupport.call;
import static com.egoge.ai.atlas.processor.RestTestSupport.compile;
import static com.egoge.ai.atlas.processor.RestTestSupport.openApi;
import static com.egoge.ai.atlas.processor.RestTestSupport.routes;
import static com.egoge.ai.atlas.processor.RestTestSupport.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * {@code ai.atlas.rest}: each operation's resolved mapping is served by the generated controller
 * and described identically by the OpenAPI document, for explicit mappings, each CRUD rule and the
 * RPC fallback. The controller's routes are read from Spring's own annotations, and real requests
 * are dispatched through Spring MVC's {@link MockMvc}.
 */
class RestMappingTest {

    @Test
    void explicitMappingsAreServedAsDeclaredAndOpenApiDescribesTheSameOperations() throws Exception {
        Compilation compilation = compile(List.of(REST_ON), ORDER, ORDER_SERVICE);
        GeneratedClasses classes = new GeneratedClasses(compilation);

        List<String> expected = List.of(
                "DELETE /api/v1/orders/{id} 204 [path:id]",
                "GET /api/v1/orders 200 [query:status]",
                "GET /api/v1/orders/count 200 []",
                "GET /api/v1/orders/{id} 200 [path:id]",
                "PATCH /api/v1/orders/{id}/status 200 [path:id, query:status]",
                "POST /api/v1/orders 201 [body]");
        assertThat(routes(classes.load("shop.generated.OrderServiceRestController"))).isEqualTo(expected);
        assertThat(routes(openApi(compilation))).isEqualTo(expected);
        assertValidOpenApi(compilation);

        MockMvc mvc = classes.mvc("OrderService");
        assertThat(call(mvc, get("/api/v1/orders/7"))).isEqualTo("200 {\"id\":7,\"status\":\"NEW\"}");
        assertThat(call(mvc, get("/api/v1/orders").param("status", "NEW"))).isEqualTo("200 [{\"id\":7,\"status\":\"NEW\"}]");
        assertThat(call(mvc, post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":8,\"status\":\"PAID\"}"))).isEqualTo("201 {\"id\":8,\"status\":\"PAID\"}");
        assertThat(call(mvc, patch("/api/v1/orders/8/status").param("status", "SHIPPED")))
                .isEqualTo("200 {\"id\":8,\"status\":\"SHIPPED\"}");
        assertThat(call(mvc, delete("/api/v1/orders/8"))).isEqualTo("204 ");
        assertThat(call(mvc, get("/api/v1/orders/count"))).isEqualTo("200 1");
    }

    @Test
    void theCrudConventionAppliesEachRuleAndLeavesOtherMethodsOnTheRpcMapping() throws Exception {
        Compilation compilation = compile(List.of(REST_ON), CUSTOMER, CUSTOMER_SERVICE);
        GeneratedClasses classes = new GeneratedClasses(compilation);

        List<String> expected = List.of(
                "DELETE /api/v1/customers/{id} 204 [path:id]",
                "GET /api/v1/customers 200 []",
                "GET /api/v1/customers/by-name/{name} 200 [path:name]",
                "GET /api/v1/customers/{id} 200 [path:id]",
                "POST /api/v1/customers 201 [body]",
                "POST /api/v1/customers/activate 200 [query:id]",
                "PUT /api/v1/customers/{id} 200 [body, path:id]");
        assertThat(routes(classes.load("shop.generated.CustomerServiceRestController"))).isEqualTo(expected);
        assertThat(routes(openApi(compilation))).isEqualTo(expected);
        assertValidOpenApi(compilation);

        MockMvc mvc = classes.mvc("CustomerService");
        assertThat(call(mvc, post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ada\"}"))).isEqualTo("201 {\"id\":1,\"name\":\"Ada\"}");
        assertThat(call(mvc, get("/api/v1/customers/1"))).isEqualTo("200 {\"id\":1,\"name\":\"Ada\"}");
        assertThat(call(mvc, put("/api/v1/customers/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Grace\"}"))).isEqualTo("200 {\"id\":1,\"name\":\"Grace\"}");
        assertThat(call(mvc, get("/api/v1/customers/by-name/Grace"))).isEqualTo("200 {\"id\":1,\"name\":\"Grace\"}");
        assertThat(call(mvc, get("/api/v1/customers"))).isEqualTo("200 [{\"id\":1,\"name\":\"Grace\"}]");
        assertThat(call(mvc, post("/api/v1/customers/activate").param("id", "1")))
                .isEqualTo("200 {\"id\":1,\"name\":\"Grace\"}");
        assertThat(call(mvc, delete("/api/v1/customers/1"))).isEqualTo("204 ");
        assertThat(call(mvc, get("/api/v1/customers"))).isEqualTo("200 []");
    }

    @Test
    void theCrudRulesMatchOnlyTheDocumentedNamesAndShapes() throws Exception {
        String service = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.*;
                import java.util.*;
                @AgenticExposed(description = "Orders", rest = @Rest(style = RestStyle.CRUD))
                public class OrderService {
                    public List<Order> list() { return List.of(); }
                    public Order getById(String code) { return null; }
                    public boolean delete(Long id) { return true; }
                    public Order findByStatus(String status) { return null; }
                    public Order save(Order order) { return order; }
                    public Order create(String status) { return null; }
                    public Order findById(Order probe) { return probe; }
                }
                """;
        Compilation compilation = compile(List.of(REST_ON), ORDER, service);

        List<String> expected = List.of(
                "DELETE /api/v1/order-service/{id} 200 [path:id]",
                "GET /api/v1/order-service 200 []",
                "GET /api/v1/order-service/{code} 200 [path:code]",
                "POST /api/v1/order-service/create 200 [query:status]",
                "POST /api/v1/order-service/find-by-id 200 [query:probe]",
                "POST /api/v1/order-service/find-by-status 200 [query:status]",
                "POST /api/v1/order-service/save 200 [query:order]");
        assertThat(routes(new GeneratedClasses(compilation).load("shop.generated.OrderServiceRestController")))
                .isEqualTo(expected);
        assertThat(routes(openApi(compilation))).isEqualTo(expected);
    }

    @Test
    void explicitMetadataWinsOverTheConventionAttributeByAttribute() throws Exception {
        String service = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.*;
                import java.util.*;
                @AgenticExposed(description = "Customers", returnType = Customer.class,
                        rest = @Rest(style = RestStyle.CRUD, resource = "customers"))
                public class CustomerService {
                    @AgenticExposed(rest = @Rest(path = "/by-id/{id}"))
                    public Customer findById(Long id) { return null; }
                    @AgenticExposed(rest = @Rest(status = 202))
                    public Customer create(Customer customer) { return customer; }
                    @AgenticExposed(rest = @Rest(method = HttpMethod.POST, path = "/{id}/delete"))
                    public void deleteById(Long id) { }
                    @AgenticExposed(rest = @Rest(method = HttpMethod.PATCH))
                    public Customer update(Long id, Customer customer) { return customer; }
                }
                """;
        Compilation compilation = compile(List.of(REST_ON), CUSTOMER, service);

        List<String> expected = List.of(
                // An explicit path keeps the rule's method and status
                "GET /api/v1/customers/by-id/{id} 200 [path:id]",
                // An explicit POST drops the rule's 204, which holds only for its DELETE
                "PATCH /api/v1/customers/{id} 200 [body, path:id]",
                "POST /api/v1/customers 202 [body]",
                "POST /api/v1/customers/{id}/delete 200 [path:id]");
        assertThat(routes(new GeneratedClasses(compilation).load("shop.generated.CustomerServiceRestController")))
                .isEqualTo(expected);
        assertThat(routes(openApi(compilation))).isEqualTo(expected);
    }

    @Test
    void theRpcFallbackKeepsEveryParameterInTheQueryIncludingAnEntity() throws Exception {
        String service = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.*;
                @AgenticExposed(description = "Orders", rest = @Rest(resource = "orders"))
                public class OrderService {
                    public Order find(Long id, String status) { return null; }
                    public Order check(Order order) { return order; }
                    public long count() { return 0; }
                    public void remove(Long id) { }
                }
                """;
        Compilation compilation = compile(List.of(REST_ON), ORDER, service);

        List<String> expected = List.of(
                "GET /api/v1/orders/count 200 []",
                "POST /api/v1/orders/check 200 [query:order]",
                "POST /api/v1/orders/find 200 [query:id, query:status]",
                "POST /api/v1/orders/remove 200 [query:id]");
        assertThat(routes(new GeneratedClasses(compilation).load("shop.generated.OrderServiceRestController")))
                .isEqualTo(expected);
        assertThat(routes(openApi(compilation))).isEqualTo(expected);
        assertThat(source(compilation, "shop.generated.OrderServiceRestController"))
                .doesNotContain("RequestBody").doesNotContain("PathVariable").doesNotContain("ResponseStatus");
    }

    @Test
    void agenticParamInPlacesAParameterInThePathQueryOrBody() throws Exception {
        String service = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.*;
                import com.egoge.ai.atlas.annotations.AgenticParam.In;
                import java.util.*;
                @AgenticExposed(description = "Orders", rest = @Rest(resource = "orders"))
                public class OrderService {
                    @AgenticExposed(returnType = Order.class, rest = @Rest(method = HttpMethod.PUT, path = "/{id}"))
                    public Order replace(@AgenticParam(in = In.PATH) Long id, @AgenticParam(in = In.QUERY) Order order) {
                        return order;
                    }
                    @AgenticExposed(rest = @Rest(method = HttpMethod.POST, path = "/notes"))
                    public int note(@AgenticParam(in = In.BODY) List<Integer> lines, String author) { return lines.size(); }
                }
                """;
        Compilation compilation = compile(List.of(REST_ON), ORDER, service);
        GeneratedClasses classes = new GeneratedClasses(compilation);

        List<String> expected = List.of(
                "POST /api/v1/orders/notes 200 [body, query:author]",
                "PUT /api/v1/orders/{id} 200 [path:id, query:order]");
        assertThat(routes(classes.load("shop.generated.OrderServiceRestController"))).isEqualTo(expected);
        assertThat(routes(openApi(compilation))).isEqualTo(expected);
        JsonNode body = openApi(compilation).path("paths").path("/api/v1/orders/notes").path("post").path("requestBody");
        assertThat(body.path("content").path("application/json").path("schema").toString())
                .isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"integer\",\"format\":\"int32\"}}");

        MockMvc mvc = classes.mvc("OrderService");
        assertThat(call(mvc, post("/api/v1/orders/notes").param("author", "ada")
                .contentType(MediaType.APPLICATION_JSON).content("[1,2,3]"))).isEqualTo("200 3");
    }

    @Test
    void aCreatedResponseCarriesNoLocationHeader() throws Exception {
        Compilation compilation = compile(List.of(REST_ON), ORDER, ORDER_SERVICE);
        MockMvc mvc = new GeneratedClasses(compilation).mvc("OrderService");

        MockHttpServletResponse response = mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":9,\"status\":\"NEW\"}")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getHeader("Location")).isNull();
        JsonNode created = openApi(compilation).path("paths").path("/api/v1/orders").path("post").path("responses")
                .path("201");
        assertThat(created.has("headers")).isFalse();
        assertThat(created.path("description").asText()).isEqualTo("Created");
    }

    @Test
    void aNoContentResponseIsDescribedWithoutContent() throws Exception {
        JsonNode noContent = openApi(compile(List.of(REST_ON), ORDER, ORDER_SERVICE)).path("paths")
                .path("/api/v1/orders/{id}").path("delete").path("responses").path("204");

        assertThat(noContent.path("description").asText()).isEqualTo("No Content");
        assertThat(noContent.has("content")).isFalse();
    }

    @Test
    void aTemplatedRouteIsInTheDeprecationManifest() {
        String service = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.*;
                @AgenticExposed(description = "Orders", returnType = Order.class,
                        rest = @Rest(style = RestStyle.CRUD, resource = "orders"))
                public class OrderService {
                    @AgenticExposed(apiDeprecatedSince = 1, apiReplacement = "cancel")
                    public void deleteById(Long id) { }
                    public Order findById(Long id) { return null; }
                }
                """;
        String manifest = RestTestSupport.resource(compile(List.of(REST_ON), ORDER, service), RestTestSupport.MANIFEST);

        assertThat(manifest)
                .contains("{\"method\":\"DELETE\",\"path\":\"/api/v1/orders/{id}\",\"deprecated\":true,"
                        + "\"deprecatedSince\":1,\"replacement\":\"cancel\"}")
                .contains("{\"method\":\"GET\",\"path\":\"/api/v1/orders/{id}\",\"deprecated\":false,");
    }
}
