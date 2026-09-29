/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.contract.ContractGate;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.IrJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>Spike (Phase 5, epic #23 §9).</strong> The compile-time and contract side of the
 * {@code ai.atlas.rest} prototype: route collisions and invalid declarations are compile errors,
 * the Contract IR records the resolved mapping, and the gate classifies verb, path, status and
 * parameter-location changes.
 */
class RestSpikeContractTest extends RestSpikeSupport {

    // ------------------------------------------------------------ collisions and validation

    @Test
    void routesThatDifferOnlyByVariableNamesCollide() {
        String service = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.*;
                @AgenticExposed(description = "Customers", returnType = Customer.class,
                        rest = @Rest(style = RestStyle.CRUD, resource = "customers"))
                public class CustomerService {
                    public Customer findById(Long id) { return null; }
                    @AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = "/{number}"))
                    public Customer byNumber(Long number) { return null; }
                    @AgenticExposed(rest = @Rest(method = HttpMethod.POST, path = "/activate"))
                    public Customer enable(Long id) { return null; }
                    public Customer activate(Long id) { return null; }
                }
                """;
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions(REST_ON, PARAMETERS).compile(source("shop.Customer", CUSTOMER),
                        source("shop.CustomerService", service));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(errors(compilation)).anySatisfy(e -> assertThat(e)
                .contains("REST mapping GET /api/v1/customers/{} of shop.CustomerService#findById")
                .contains("is also mapped by shop.CustomerService#byNumber"));
        assertThat(errors(compilation)).anySatisfy(e -> assertThat(e)
                .contains("REST mapping POST /api/v1/customers/activate of shop.CustomerService#enable")
                .contains("is also mapped by shop.CustomerService#activate"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            path without a leading slash   | | @AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = "orders")) public String a() { return null; } | must be empty or '/'-separated segments
            variable naming no parameter   | | @AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = "/{id}")) public String a() { return null; } | Path variable {id} of 'a' names no parameter
            repeated variable              | | @AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = "/{id}/{id}")) public String a(Long id) { return null; } | appears more than once
            path parameter without {name}  | | @AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = "")) public String a(@AgenticParam(in = In.PATH) Long id) { return null; } | is in the path, but the path "/bad-service" has no {id}
            variable declared elsewhere    | | @AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = "/{id}")) public String a(@AgenticParam(in = In.QUERY) Long id) { return null; } | is named by the path variable {id} but declared in QUERY
            non-scalar path parameter      | | @AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = "/{ids}")) public String a(List<Long> ids) { return null; } | must be a scalar
            two bodies                     | | @AgenticExposed(rest = @Rest(method = HttpMethod.POST, path = "")) public String a(Order x, Order y) { return null; } | at most one parameter may be in the body
            body on GET                    | | @AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = "")) public String a(Order order) { return null; } | GET 'a' must not have a request body
            body on DELETE                 | | @AgenticExposed(rest = @Rest(method = HttpMethod.DELETE, path = "")) public void a(@AgenticParam(in = In.BODY) String note) { } | DELETE 'a' must not have a request body
            non-2xx status                 | | @AgenticExposed(rest = @Rest(status = 404)) public String a() { return null; } | must be a 2xx success status
            2xx without a Spring constant  | | @AgenticExposed(rest = @Rest(status = 299)) public String a() { return null; } | must be a 2xx success status
            204 with a response            | | @AgenticExposed(rest = @Rest(status = 204)) public String a() { return null; } | its status is 204 No Content, which carries no body
            method attributes on the class | @AgenticExposed(rest = @Rest(path = "/x")) | public String a() { return null; } | declare them on each method
            invalid resource               | @AgenticExposed(rest = @Rest(resource = "a/b")) | public String a() { return null; } | must be one path segment
            style on a method              | | @AgenticExposed(rest = @Rest(style = RestStyle.CRUD)) public String a() { return null; } | declare them on the service class
            """)
    void invalidDeclarationsAreCompileErrors(String name, String classAnnotation, String member, String message) {
        Compilation compilation = compileBad(List.of(REST_ON), classAnnotation, member);

        assertThat(compilation.status()).as(name).isEqualTo(Compilation.Status.FAILURE);
        assertThat(errors(compilation)).as(name).anySatisfy(e -> assertThat(e).contains(message));
    }

    @Test
    void declarationsWithTheOptionOffAreAnError() {
        Compilation compilation = compileBad(List.of(), null,
                "@AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = \"/{id}\")) public String a(Long id) { return null; }");

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(errors(compilation)).anySatisfy(e -> assertThat(e).contains(
                "REST metadata on 'a' has no effect unless ai.atlas.rest=true: the operation would still be served at"
                        + " POST /a"));
    }

    // ------------------------------------------------------------ Contract IR and gate

    @Test
    void contractIrRecordsTheResolvedMapping() throws IOException {
        JsonNode ir = JSON.readTree(ir(compile(List.of(REST_ON), CUSTOMER, CUSTOMER_SERVICE)));
        Map<String, String> rest = new TreeMap<>();
        ir.path("operations").forEach(op -> rest.put(op.path("method").asText(), op.path("rest").toString()));

        assertThat(rest).containsEntry("create",
                        "{\"httpMethod\":\"POST\",\"path\":\"/customers\",\"status\":201,\"parameterIn\":[\"BODY\"]}")
                .containsEntry("update",
                        "{\"httpMethod\":\"PUT\",\"path\":\"/customers/{id}\",\"status\":200,\"parameterIn\":[\"PATH\",\"BODY\"]}")
                .containsEntry("deleteById",
                        "{\"httpMethod\":\"DELETE\",\"path\":\"/customers/{id}\",\"status\":204,\"parameterIn\":[\"PATH\"]}")
                .containsEntry("activate",
                        "{\"httpMethod\":\"POST\",\"path\":\"/customers/activate\",\"status\":200,\"parameterIn\":[\"QUERY\"]}");
        assertThat(ir.path("irVersion").asInt()).isEqualTo(ContractIr.IR_VERSION);
    }

    private static final String GATE_SERVICE = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import com.egoge.ai.atlas.annotations.AgenticExposed.*;
            import com.egoge.ai.atlas.annotations.AgenticParam.In;
            @AgenticExposed(returnType = Order.class, channels = AgenticExposed.Channel.API, rest = @Rest(resource = "orders"))
            public class OrderService {
                @AgenticExposed(description = "Place", rest = @Rest(method = HttpMethod.%s, path = "", status = %s))
                public Order place(Order order) { return order; }
                @AgenticExposed(description = "Get", rest = @Rest(method = HttpMethod.GET, path = "%s"))
                public Order get(Long %s) { return null; }
                @AgenticExposed(description = "Search", rest = @Rest(method = HttpMethod.POST, path = "/search"))
                public Order search(@AgenticParam(in = In.%s) String q) { return null; }
            }
            """;

    private static String gateService(String verb, int status, String getPath, String getParam, String searchIn) {
        return GATE_SERVICE.formatted(verb, status, getPath, getParam, searchIn);
    }

    @Test
    void gateClassifiesChangedVerbPathStatusAndLocationAsBreaking() throws Exception {
        ContractIr baseline = irOf(gateService("POST", 201, "/{id}", "id", "QUERY"));

        assertThat(changes(baseline, irOf(gateService("PUT", 201, "/{id}", "id", "QUERY"))))
                .containsExactly("BREAKING rest.httpMethod POST -> PUT");
        assertThat(changes(baseline, irOf(gateService("POST", 200, "/{id}", "id", "QUERY"))))
                .containsExactly("BREAKING rest.status 201 -> 200");
        assertThat(changes(baseline, irOf(gateService("POST", 201, "/by-id/{id}", "id", "QUERY"))))
                .containsExactly("BREAKING rest.path /orders/{id} -> /orders/by-id/{id}");
        assertThat(changes(baseline, irOf(gateService("POST", 201, "/{id}", "id", "BODY"))))
                .containsExactly("BREAKING parameter 0.in QUERY -> BODY");
        // Renaming a path variable keeps the route; the gate still reports the parameter's new name,
        // which the MCP input and the OpenAPI parameter carry (open question Q7)
        assertThat(changes(baseline, irOf(gateService("POST", 201, "/{orderId}", "orderId", "QUERY"))))
                .containsExactly("BREAKING parameter 0.name id -> orderId",
                        "COMPATIBLE rest.path /orders/{id} -> /orders/{orderId}");
    }

    @Test
    void turningTheOptionOnWithoutDeclarationsIsNoContractChange() throws Exception {
        ContractIr off = IrJson.parse(ir(compile(List.of(), CUSTOMER, plainService())), "off");
        ContractIr on = IrJson.parse(ir(compile(List.of(REST_ON), CUSTOMER, plainService())), "on");

        assertThat(ContractGate.compare(off, on)).isEmpty();
        assertThat(ContractGate.compare(on, off)).isEmpty();
        // But lock mode compares documents, and the spike records status and locations only with the
        // option on: every API operation differs. An irVersion 4 with always-present slots and an
        // exact migration closes this (open question Q7)
        assertThat(ContractGate.documentDifferences(off, on)).containsExactly(
                "operation shop.CustomerService#activate(java.lang.Long,java.lang.String)",
                "operation shop.CustomerService#deleteById(java.lang.Long)",
                "operation shop.CustomerService#findAll()",
                "operation shop.CustomerService#findById(java.lang.Long)",
                "operation shop.CustomerService#rename(java.lang.Long,java.lang.String)");
    }

}
