/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.egoge.ai.atlas.processor.RestTestSupport.CONSTRAINTS_ON;
import static com.egoge.ai.atlas.processor.RestTestSupport.CUSTOMER;
import static com.egoge.ai.atlas.processor.RestTestSupport.CUSTOMER_SERVICE;
import static com.egoge.ai.atlas.processor.RestTestSupport.ORDER;
import static com.egoge.ai.atlas.processor.RestTestSupport.ORDER_SERVICE;
import static com.egoge.ai.atlas.processor.RestTestSupport.REST_OFF;
import static com.egoge.ai.atlas.processor.RestTestSupport.REST_ON;
import static com.egoge.ai.atlas.processor.RestTestSupport.assertSameOutputsButTheRecordedFlag;
import static com.egoge.ai.atlas.processor.RestTestSupport.compile;
import static com.egoge.ai.atlas.processor.RestTestSupport.mcpOutputs;
import static com.egoge.ai.atlas.processor.RestTestSupport.outputs;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ai.atlas.rest} changes nothing it does not declare: the MCP tool classes and
 * {@code mcp-tools.json} are the same with and without REST metadata, and with the option on and
 * nothing declared every generated source and resource is byte-identical to the option off.
 * {@code IrRewireGoldenTest} holds the same for its fixtures and the demo against the golden
 * snapshot.
 */
class RestUnchangedOutputTest {

    /** {@link RestTestSupport#ORDER_SERVICE} without any REST declaration. */
    private static final String PLAIN_ORDER_SERVICE = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import java.util.*;
            @AgenticExposed
            public class OrderService {
                public static Order lastPlaced;
                private final Map<Long, Order> orders = new TreeMap<>(Map.of(7L, new Order(7L, "NEW")));
                @AgenticExposed(description = "Order by id", returnType = Order.class)
                public Order get(Long id) { return orders.get(id); }
                @AgenticExposed(description = "Orders by status", returnType = Order.class)
                public List<Order> byStatus(String status) {
                    return orders.values().stream().filter(o -> o.getStatus().equals(status)).toList();
                }
                @AgenticExposed(description = "Place an order", returnType = Order.class)
                public Order place(Order order) { lastPlaced = order; orders.put(order.getId(), order); return order; }
                @AgenticExposed(description = "Change an order's status", returnType = Order.class)
                public Order changeStatus(Long id, String status) {
                    Order o = orders.get(id); o.setStatus(status); return o;
                }
                @AgenticExposed(description = "Cancel an order")
                public void cancel(Long id) { orders.remove(id); }
                @AgenticExposed(description = "Number of orders")
                public long count() { return orders.size(); }
            }
            """;

    @Test
    void restMetadataLeavesTheMcpToolsUnchanged() {
        for (List<String> options : List.of(List.of(REST_ON), List.of(REST_ON, CONSTRAINTS_ON))) {
            Map<String, String> declared = mcpOutputs(compile(options, ORDER, ORDER_SERVICE));
            Map<String, String> plain = mcpOutputs(compile(options, ORDER, PLAIN_ORDER_SERVICE));

            assertThat(declared).isNotEmpty().isEqualTo(plain);
        }
    }

    @Test
    void theCrudConventionLeavesTheMcpToolsUnchanged() {
        String plain = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.*;
                import java.util.*;
                @AgenticExposed(description = "Customers", returnType = Customer.class)
                public class CustomerService {
                    private final Map<Long, Customer> store = new TreeMap<>();
                    private long next = 1;
                    public List<Customer> findAll() { return new ArrayList<>(store.values()); }
                    public Customer findById(Long id) { return store.get(id); }
                    public Customer create(Customer customer) {
                        customer.setId(next++); store.put(customer.getId(), customer); return customer;
                    }
                    public Customer update(Long id, Customer customer) { customer.setId(id); store.put(id, customer); return customer; }
                    public void deleteById(Long id) { store.remove(id); }
                    @AgenticExposed(description = "Customer by name")
                    public Customer findByName(String name) {
                        return store.values().stream().filter(c -> c.getName().equals(name)).findFirst().orElse(null);
                    }
                    public Customer activate(Long id) { return store.get(id); }
                }
                """;
        List<String> options = List.of(REST_ON, CONSTRAINTS_ON);

        assertThat(mcpOutputs(compile(options, CUSTOMER, CUSTOMER_SERVICE)))
                .isNotEmpty().isEqualTo(mcpOutputs(compile(options, CUSTOMER, plain)));
    }

    @Test
    void theOptionOnWithNothingDeclaredGeneratesByteIdenticalOutput() {
        String customer = CUSTOMER.replace(", input = false", "");
        for (List<String> extra : List.of(List.<String>of(), List.of(CONSTRAINTS_ON))) {
            Map<String, String> off = outputs(compile(extra, ORDER, PLAIN_ORDER_SERVICE, customer), false);
            Map<String, String> explicitOff = outputs(compile(with(extra, REST_OFF), ORDER, PLAIN_ORDER_SERVICE,
                    customer), false);
            Map<String, String> on = outputs(compile(with(extra, REST_ON), ORDER, PLAIN_ORDER_SERVICE, customer), false);

            assertThat(off).isNotEmpty().isEqualTo(explicitOff);
            assertSameOutputsButTheRecordedFlag(on, off, "ai.atlas.rest");
        }
    }

    private static List<String> with(List<String> options, String option) {
        return Stream.concat(options.stream(), Stream.of(option)).toList();
    }
}
