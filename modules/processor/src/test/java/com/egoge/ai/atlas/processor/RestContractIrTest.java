/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.contract.ContractGate;
import com.egoge.ai.atlas.processor.contract.ContractGate.Classification;
import com.egoge.ai.atlas.processor.contract.ContractGate.Difference;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.IrJson;
import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.egoge.ai.atlas.processor.RestTestSupport.CUSTOMER;
import static com.egoge.ai.atlas.processor.RestTestSupport.CUSTOMER_SERVICE;
import static com.egoge.ai.atlas.processor.RestTestSupport.ORDER;
import static com.egoge.ai.atlas.processor.RestTestSupport.ORDER_SERVICE;
import static com.egoge.ai.atlas.processor.RestTestSupport.REST_ON;
import static com.egoge.ai.atlas.processor.RestTestSupport.compile;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ai.atlas.rest} and Contract IR version 4: each API operation's {@code rest} records the
 * effective mapping the controller serves, its status and each parameter's location included;
 * turning the option on without declaring anything changes no byte of the IR; and the gate
 * classifies the changes a declaration makes.
 */
class RestContractIrTest {

    private static final String ORDERS = "shop.OrderService#";

    @Test
    void theIrRecordsTheEffectiveMappingOfEachApiOperation() throws Exception {
        Map<String, String> orders = mappings(ir(List.of(REST_ON), ORDER, ORDER_SERVICE));

        assertThat(orders).containsExactlyInAnyOrderEntriesOf(Map.of(
                ORDERS + "byStatus(java.lang.String)", "GET /orders 200 [QUERY]",
                ORDERS + "cancel(java.lang.Long)", "DELETE /orders/{id} 204 [PATH]",
                ORDERS + "changeStatus(java.lang.Long,java.lang.String)", "PATCH /orders/{id}/status 200 [PATH, QUERY]",
                ORDERS + "count()", "GET /orders/count 200 []",
                ORDERS + "get(java.lang.Long)", "GET /orders/{id} 200 [PATH]",
                ORDERS + "place(shop.Order)", "POST /orders 201 [BODY]"));

        Map<String, String> customers = mappings(ir(List.of(REST_ON), CUSTOMER, CUSTOMER_SERVICE));
        assertThat(customers).containsEntry("shop.CustomerService#create(shop.Customer)", "POST /customers 201 [BODY]")
                .containsEntry("shop.CustomerService#update(java.lang.Long,shop.Customer)",
                        "PUT /customers/{id} 200 [PATH, BODY]")
                .containsEntry("shop.CustomerService#deleteById(java.lang.Long)", "DELETE /customers/{id} 204 [PATH]")
                .containsEntry("shop.CustomerService#activate(java.lang.Long)", "POST /customers/activate 200 [QUERY]");
    }

    @Test
    void theOptionOnWithNothingDeclaredWritesTheSameIr() throws Exception {
        String service = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticExposed(description = "Orders")
                public class OrderService {
                    public Order find(Long id, String status) { return null; }
                    public void remove(Long id) { }
                    public long count() { return 0; }
                }
                """;

        String off = RestTestSupport.resource(compile(List.of(), ORDER, service), ContractIr.RESOURCE_PATH);
        String on = RestTestSupport.resource(compile(List.of(REST_ON), ORDER, service), ContractIr.RESOURCE_PATH);

        assertThat(on).isEqualTo(off);
        assertThat(mappings(IrJson.parse(on, "test"))).containsExactlyInAnyOrderEntriesOf(Map.of(
                ORDERS + "count()", "GET /order-service/count 200 []",
                ORDERS + "find(java.lang.Long,java.lang.String)", "POST /order-service/find 200 [QUERY, QUERY]",
                ORDERS + "remove(java.lang.Long)", "POST /order-service/remove 200 [QUERY]"));
    }

    @Test
    void theGateClassifiesTheChangesADeclarationMakes() throws Exception {
        String plain = CUSTOMER_SERVICE.replace("rest = @Rest(style = RestStyle.CRUD, resource = \"customers\")",
                "rest = @Rest(resource = \"customers\")");
        ContractIr baseline = ir(List.of(REST_ON), CUSTOMER, plain);
        ContractIr fresh = ir(List.of(REST_ON), CUSTOMER, CUSTOMER_SERVICE);

        List<String> changes = changes(baseline, fresh, "deleteById(java.lang.Long)");

        assertThat(changes).containsExactlyInAnyOrder(
                "BREAKING INPUT rest.httpMethod POST -> DELETE",
                "BREAKING INPUT rest.path /customers/delete-by-id -> /customers/{id}",
                "BREAKING OUTPUT rest.status 200 -> 204",
                "BREAKING INPUT parameter 0.in QUERY -> PATH");
    }

    @Test
    void renamingAPathVariableAndItsParameterIsCompatibleOnlyOffTheAiChannel() throws Exception {
        String service = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.*;
                @AgenticExposed(rest = @Rest(resource = "orders"))
                public class OrderService {
                    @AgenticExposed(description = "Get", channels = %s,
                            rest = @Rest(method = HttpMethod.GET, path = "/{%s}"))
                    public void get(Long %s) { }
                }
                """;
        for (String channels : List.of("Channel.API", "{Channel.AI, Channel.API}")) {
            ContractIr baseline = ir(List.of(REST_ON), ORDER, service.formatted(channels, "id", "id"));
            ContractIr fresh = ir(List.of(REST_ON), ORDER, service.formatted(channels, "orderId", "orderId"));

            List<String> changes = changes(baseline, fresh, "get(java.lang.Long)");

            assertThat(changes).contains("COMPATIBLE INPUT rest.path /orders/{id} -> /orders/{orderId}");
            assertThat(changes).contains(("Channel.API".equals(channels) ? "COMPATIBLE" : "BREAKING")
                    + " INPUT parameter 0.name id -> orderId");
        }
    }

    private static ContractIr ir(List<String> options, String... sources) throws Exception {
        Compilation compilation = compile(options, sources);
        return IrJson.parse(RestTestSupport.resource(compilation, ContractIr.RESOURCE_PATH), "test");
    }

    /** {@code METHOD path status [locations]} of each API operation, by its identity. */
    private static Map<String, String> mappings(ContractIr ir) {
        Map<String, String> result = new TreeMap<>();
        for (ContractIr.Operation op : ir.operations()) {
            if (op.rest() != null) {
                result.put(op.id(), op.rest().httpMethod() + " " + op.rest().path() + " " + op.rest().status() + " "
                        + op.rest().parameterIn());
            }
        }
        return result;
    }

    /** {@code CLASSIFICATION DIRECTION change before -> after} of each difference of one operation, informational ones aside. */
    private static List<String> changes(ContractIr baseline, ContractIr fresh, String signature) {
        return ContractGate.compare(baseline, fresh).stream()
                .filter(d -> d.path().endsWith(signature) && d.classification() != Classification.INFORMATIONAL)
                .map((Difference d) -> d.classification() + " " + d.direction() + " " + d.change() + " "
                        + d.before() + " -> " + d.after())
                .toList();
    }
}
