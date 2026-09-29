/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.generator.McpToolsResourceGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * <strong>Spike (Phase 5, epic #23 §9).</strong> Proves the REST prototype behind
 * {@code ai.atlas.rest}: explicit {@code @AgenticExposed(rest = @Rest(...))} and
 * {@code @AgenticParam(in)} metadata, and the opt-in CRUD convention, resolve once into the
 * Contract IR's {@code rest} mapping, from which the controller and the OpenAPI document are both
 * generated and agree; route collisions and invalid declarations are compile errors; the gate
 * classifies verb, path, status and location changes; MCP output is unchanged; and with the
 * option on and nothing declared, the output is byte-identical to the option off.
 *
 * <p>The generated controllers are loaded and called through Spring MVC's {@link MockMvc}, so the
 * routes, bindings and statuses are Spring's own, not a reading of the source.
 */
class RestSpikeTest extends RestSpikeSupport {

    // ------------------------------------------------------------ controller and OpenAPI agree

    @Test
    void explicitMappingsAreServedAsDeclaredAndOpenApiDescribesTheSameOperations() throws Exception {
        Compilation compilation = compile(List.of(REST_ON), ORDER, ORDER_SERVICE);
        GeneratedClasses classes = new GeneratedClasses(compilation);

        assertThat(routes(classes.load("shop.generated.OrderServiceRestController"))).containsExactly(
                "DELETE /api/v1/orders/{id} 204 [path:id]",
                "GET /api/v1/orders 200 [query:status]",
                "GET /api/v1/orders/count 200 []",
                "GET /api/v1/orders/{id} 200 [path:id]",
                "PATCH /api/v1/orders/{id}/status 200 [path:id, query:status]",
                "POST /api/v1/orders 201 [body]");
        assertThat(routes(openApi(compilation))).isEqualTo(routes(classes.load("shop.generated.OrderServiceRestController")));
        assertValidOpenApi(compilation);

        MockMvc mvc = classes.mvc("shop.OrderService", "shop.generated.OrderServiceRestController");
        assertThat(call(mvc, get("/api/v1/orders/7"))).isEqualTo("200 {\"id\":7,\"status\":\"NEW\"}");
        assertThat(call(mvc, get("/api/v1/orders").param("status", "NEW")))
                .isEqualTo("200 [{\"id\":7,\"status\":\"NEW\"}]");
        assertThat(call(mvc, post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":8,\"status\":\"NEW\"}"))).isEqualTo("201 {\"id\":8,\"status\":\"NEW\"}");
        assertThat(call(mvc, patch("/api/v1/orders/7/status").param("status", "SHIPPED")))
                .isEqualTo("200 {\"id\":7,\"status\":\"SHIPPED\"}");
        assertThat(call(mvc, delete("/api/v1/orders/7"))).isEqualTo("204 ");
        assertThat(call(mvc, get("/api/v1/orders/count"))).isEqualTo("200 1");
    }

    @Test
    void crudConventionDerivesVerbPathAndStatusAndLeavesOtherMethodsRpc() throws Exception {
        Compilation compilation = compile(List.of(REST_ON), CUSTOMER, CUSTOMER_SERVICE);
        GeneratedClasses classes = new GeneratedClasses(compilation);
        Class<?> controller = classes.load("shop.generated.CustomerServiceRestController");

        assertThat(routes(controller)).containsExactly(
                "DELETE /api/v1/customers/{id} 204 [path:id]",
                "GET /api/v1/customers 200 []",
                "GET /api/v1/customers/by-name/{name} 200 [path:name]",
                "GET /api/v1/customers/{id} 200 [path:id]",
                "POST /api/v1/customers 201 [body]",
                "POST /api/v1/customers/activate 200 [query:id]",
                "PUT /api/v1/customers/{id} 200 [body, path:id]");
        assertThat(routes(openApi(compilation))).isEqualTo(routes(controller));
        assertValidOpenApi(compilation);

        MockMvc mvc = classes.mvc("shop.CustomerService", "shop.generated.CustomerServiceRestController");
        assertThat(call(mvc, post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":1,\"name\":\"Ada\"}"))).isEqualTo("201 {\"id\":1,\"name\":\"Ada\"}");
        assertThat(call(mvc, put("/api/v1/customers/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Grace\"}"))).isEqualTo("200 {\"id\":1,\"name\":\"Grace\"}");
        assertThat(call(mvc, get("/api/v1/customers/by-name/Grace"))).isEqualTo("200 {\"id\":1,\"name\":\"Grace\"}");
        assertThat(call(mvc, get("/api/v1/customers"))).isEqualTo("200 [{\"id\":1,\"name\":\"Grace\"}]");
        assertThat(call(mvc, delete("/api/v1/customers/1"))).isEqualTo("204 ");
        assertThat(call(mvc, get("/api/v1/customers/1"))).isEqualTo("200 ");
    }

    @Test
    void explicitMetadataWinsOverTheConventionAttributeByAttribute() throws Exception {
        String service = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.*;
                @AgenticExposed(description = "Customers", returnType = Customer.class,
                        rest = @Rest(style = RestStyle.CRUD, resource = "customers"))
                public class CustomerService {
                    @AgenticExposed(rest = @Rest(path = "/lookup/{id}"))
                    public Customer findById(Long id) { return null; }
                    @AgenticExposed(rest = @Rest(status = 202))
                    public Customer create(Customer customer) { return customer; }
                    @AgenticExposed(rest = @Rest(method = HttpMethod.POST))
                    public void deleteById(Long id) { }
                }
                """;
        Compilation compilation = compile(List.of(REST_ON), CUSTOMER, service);
        Class<?> controller = new GeneratedClasses(compilation).load("shop.generated.CustomerServiceRestController");

        // path explicit, verb from the rule; status explicit; verb explicit, so the rule's 204 no longer holds
        assertThat(routes(controller)).containsExactly(
                "GET /api/v1/customers/lookup/{id} 200 [path:id]",
                "POST /api/v1/customers 202 [body]",
                "POST /api/v1/customers/{id} 200 [path:id]");
        assertThat(routes(openApi(compilation))).isEqualTo(routes(controller));
    }

    // ------------------------------------------------------------ MCP is unaffected

    @Test
    void mcpToolAndToolSpecificationsAreUnchangedByRestMetadata() {
        String undeclared = ORDER_SERVICE.replaceAll("(,\\s*)?rest = @Rest\\([^)]*\\)", "");
        assertThat(undeclared).doesNotContain("@Rest(");
        Map<String, String> declared = mcpOutputs(compile(List.of(REST_ON, "-Aai.atlas.constraints=true"),
                ORDER, ORDER_SERVICE));
        Map<String, String> before = mcpOutputs(compile(List.of("-Aai.atlas.constraints=true"), ORDER, undeclared));

        assertThat(declared).containsOnlyKeys("shop.generated.OrderServiceMcpTool", McpToolsResourceGenerator.RESOURCE_PATH);
        assertThat(declared).isEqualTo(before);
    }


    // ------------------------------------------------------------ byte-identical output

    @Test
    void optionOnWithoutDeclarationsGeneratesByteIdenticalSourcesAndResources() throws IOException {
        Compilation off = compile(List.of(), CUSTOMER, plainService());
        Compilation on = compile(List.of(REST_ON), CUSTOMER, plainService());

        Map<String, String> offFiles = outputs(off);
        Map<String, String> onFiles = outputs(on);
        String irKey = offFiles.keySet().stream().filter(k -> k.endsWith(ContractIr.RESOURCE_PATH)).findFirst()
                .orElseThrow();
        assertThat(onFiles.keySet()).isEqualTo(offFiles.keySet());
        offFiles.keySet().stream().filter(k -> !k.equals(irKey))
                .forEach(k -> assertThat(onFiles.get(k)).as(k).isEqualTo(offFiles.get(k)));

        // The IR records each operation's status and parameter locations with the option on, and
        // only those: removing them gives the option-off document
        JsonNode onIr = JSON.readTree(onFiles.get(irKey));
        onIr.path("operations").forEach(op -> {
            if (op.path("rest").isObject()) {
                ((ObjectNode) op.path("rest")).remove(List.of("status", "parameterIn"));
            }
        });
        assertThat(onIr).isEqualTo(JSON.readTree(offFiles.get(irKey)));
    }

    // ------------------------------------------------------------ characterisation: entity bodies

    @Test
    void entityRequestBodyBindsPropertiesOutsideTheWhitelist() throws Exception {
        Compilation compilation = compile(List.of(REST_ON), ORDER, ORDER_SERVICE);
        GeneratedClasses classes = new GeneratedClasses(compilation);
        MockMvc mvc = classes.mvc("shop.OrderService", "shop.generated.OrderServiceRestController");

        call(mvc, post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":9,\"status\":\"NEW\",\"ssn\":\"123-45-6789\"}"));

        // The OpenAPI request body is OrderDto (id, status), but Jackson bound 'ssn' onto the entity:
        // the inbound whitelist gap open question Q3 is about
        Object placed = classes.load("shop.OrderService").getField("lastPlaced").get(null);
        assertThat(placed.getClass().getMethod("getSsn").invoke(placed)).isEqualTo("123-45-6789");
        JsonNode body = openApi(compilation).path("paths").path("/api/v1/orders").path("post").path("requestBody");
        assertThat(body.path("content").path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/OrderDto");
        assertThat(openApi(compilation).path("components").path("schemas").path("OrderDto").path("properties")
                .has("ssn")).isFalse();
    }

    @Test
    void scalarStringBodyIsBoundAsRawTextWhileOpenApiSaysJson() throws Exception {
        String service = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.*;
                import com.egoge.ai.atlas.annotations.AgenticParam.In;
                @AgenticExposed(rest = @Rest(resource = "notes"))
                public class NoteService {
                    @AgenticExposed(description = "Echo", rest = @Rest(method = HttpMethod.POST, path = "/echo"))
                    public int length(@AgenticParam(in = In.BODY) String text) { return text.length(); }
                }
                """;
        Compilation compilation = compile(List.of(REST_ON), service);
        MockMvc mvc = new GeneratedClasses(compilation).mvc("shop.NoteService", "shop.generated.NoteServiceRestController");

        // A JSON client sends the JSON string "hello"; Spring's String converter hands the service the
        // raw text, quotes included, so the service sees 7 characters, not 5 (open question Q3)
        assertThat(openApi(compilation).path("paths").path("/api/v1/notes/echo").path("post").path("requestBody")
                .path("content").path("application/json").path("schema").path("type").asText()).isEqualTo("string");
        assertThat(call(mvc, post("/api/v1/notes/echo").contentType(MediaType.APPLICATION_JSON).content("\"hello\"")))
                .isEqualTo("200 7");
    }
}
