/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.contract.ContractIr.Bound;
import com.egoge.ai.atlas.processor.contract.ContractIr.Entity;
import com.egoge.ai.atlas.processor.contract.ContractIr.Field;
import com.egoge.ai.atlas.processor.contract.ContractIr.FieldLifecycle;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;
import com.egoge.ai.atlas.processor.contract.ContractIr.OperationLifecycle;
import com.egoge.ai.atlas.processor.contract.ContractIr.Return;
import com.egoge.ai.atlas.processor.contract.ContractIr.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChannelReachability}, extracted from {@link ContractComparison#reachable(String)}:
 * which entities, fields and operations a channel's clients can receive at a major, over a
 * document built directly rather than compiled (D4.7, D4.9).
 */
class ChannelReachabilityTest {

    private static final String API = "API";
    private static final String AI = "AI";
    private static final int MAJOR = 1;
    private static final FieldLifecycle ACTIVE = new FieldLifecycle(1, Integer.MAX_VALUE, 0, null);
    private static final FieldLifecycle INACTIVE_AT_MAJOR = new FieldLifecycle(5, Integer.MAX_VALUE, 0, null);
    private static final OperationLifecycle OP_ACTIVE = new OperationLifecycle(1, Integer.MAX_VALUE, 0, null);
    private static final OperationLifecycle OP_INACTIVE_AT_MAJOR = new OperationLifecycle(5, Integer.MAX_VALUE, 0,
            null);

    @Test
    void anOperationsDirectReturnIsReachable() {
        Entity order = entity("test.Order", List.of());
        Operation find = operation("test.OrderService", "find", ref(order), List.of(API), OP_ACTIVE);
        ContractIr ir = new ContractIr(ContractIr.IR_VERSION, "/api", MAJOR, List.of(order), List.of(find));

        assertThat(ChannelReachability.entities(ir, MAJOR, API)).containsExactly("test.Order");
    }

    @Test
    void aNestedChainOfFieldsOnTheChannelIsReachable() {
        Entity address = entity("test.Address", List.of());
        Entity customer = entity("test.Customer", List.of(field("address", ref(address), List.of(API), ACTIVE)));
        Entity order = entity("test.Order", List.of(field("customer", ref(customer), List.of(API), ACTIVE)));
        Operation find = operation("test.OrderService", "find", ref(order), List.of(API), OP_ACTIVE);
        ContractIr ir = new ContractIr(ContractIr.IR_VERSION, "/api", MAJOR, List.of(order, customer, address),
                List.of(find));

        assertThat(ChannelReachability.entities(ir, MAJOR, API))
                .containsExactlyInAnyOrder("test.Order", "test.Customer", "test.Address");
        assertThat(ChannelReachability.fieldVisible(ir, MAJOR, "test.Customer", "address", API)).isTrue();
    }

    @Test
    void anIntermediateFieldExcludingTheChannelGivesNoVisibility() {
        Entity address = entity("test.Address", List.of());
        // Customer.address is on AI only, so Address is unreachable on API even though Customer is
        Entity customer = entity("test.Customer", List.of(field("address", ref(address), List.of(AI), ACTIVE)));
        Entity order = entity("test.Order", List.of(field("customer", ref(customer), List.of(API), ACTIVE)));
        Operation find = operation("test.OrderService", "find", ref(order), List.of(API), OP_ACTIVE);
        ContractIr ir = new ContractIr(ContractIr.IR_VERSION, "/api", MAJOR, List.of(order, customer, address),
                List.of(find));

        assertThat(ChannelReachability.entities(ir, MAJOR, API)).containsExactlyInAnyOrder("test.Order",
                "test.Customer");
        assertThat(ChannelReachability.fieldVisible(ir, MAJOR, "test.Customer", "address", API)).isFalse();
    }

    @Test
    void anOperationNotListingTheChannelGivesNoVisibility() {
        Entity order = entity("test.Order", List.of(field("note", null, List.of(API), ACTIVE)));
        Operation find = operation("test.OrderService", "find", ref(order), List.of(AI), OP_ACTIVE);
        ContractIr ir = new ContractIr(ContractIr.IR_VERSION, "/api", MAJOR, List.of(order), List.of(find));

        assertThat(ChannelReachability.entities(ir, MAJOR, API)).isEmpty();
        assertThat(ChannelReachability.fieldVisible(ir, MAJOR, "test.Order", "note", API)).isFalse();
        assertThat(ChannelReachability.operationListed(ir, MAJOR, find.id(), API)).isFalse();
        assertThat(ChannelReachability.operationListed(ir, MAJOR, find.id(), AI)).isTrue();
    }

    @Test
    void anElementInactiveAtTheMajorIsNotCounted() {
        Entity order = entity("test.Order", List.of(field("note", null, List.of(API), INACTIVE_AT_MAJOR)));
        Operation find = operation("test.OrderService", "find", ref(order), List.of(API), OP_INACTIVE_AT_MAJOR);
        ContractIr ir = new ContractIr(ContractIr.IR_VERSION, "/api", MAJOR, List.of(order), List.of(find));

        assertThat(ChannelReachability.entities(ir, MAJOR, API)).isEmpty();
        assertThat(ChannelReachability.fieldVisible(ir, MAJOR, "test.Order", "note", API)).isFalse();
        assertThat(ChannelReachability.operationListed(ir, MAJOR, find.id(), API)).isFalse();
    }

    // ------------------------------------------------------------ fixture helpers

    private static Entity entity(String className, List<Field> fields) {
        return new Entity(className, className.substring(className.lastIndexOf('.') + 1) + "Dto",
                "generated", className, "", false, fields);
    }

    private static Field field(String name, TypeRef reference, List<String> channels, FieldLifecycle lifecycle) {
        return new Field(name, name, "java.lang.String", "NONE", null, null, reference, false, List.of(), false,
                false, false, "", null, channels, lifecycle);
    }

    private static TypeRef ref(Entity entity) {
        return new TypeRef(entity.className(), entity.dtoPackage() + "." + entity.dtoName());
    }

    private static Operation operation(String service, String method, TypeRef returns, List<String> channels,
                                       OperationLifecycle lifecycle) {
        Return ret = new Return(returns.entity(), "NONE", returns.dto(), returns, Bound.NONE);
        return new Operation(service, method, method, channels, "", null, List.of(), ret, null, lifecycle);
    }
}
