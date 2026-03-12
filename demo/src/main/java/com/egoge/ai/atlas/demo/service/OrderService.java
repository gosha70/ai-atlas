/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.demo.service;

import com.egoge.ai.atlas.annotations.AgenticExposed;
import com.egoge.ai.atlas.demo.entity.Order;
import com.egoge.ai.atlas.demo.entity.OrderAction;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Demo service exposing order operations to AI agents.
 */
@Service
@AgenticExposed(
        toolName = "orderService",
        description = "Order management operations",
        returnType = Order.class
)
public class OrderService {

    @AgenticExposed(description = "Find order by ID (legacy)",
            returnType = Order.class,
            apiDeprecatedSince = 1, apiReplacement = "findByIdV2")
    public Order findById(Long id) {
        return buildStubOrder(id);
    }

    @AgenticExposed(description = "Find order by ID",
            returnType = Order.class, apiSince = 2)
    public Order findByIdV2(Long id) {
        return buildStubOrder(id);
    }

    public List<Order> findByStatus(String status) {
        Order.OrderStatus requested;
        try {
            requested = Order.OrderStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return List.of();
        }

        Order order = buildStubOrder(1L);
        order.setStatus(requested);
        return List.of(order);
    }

    @AgenticExposed(description = "Find orders by priority (planned for v3)",
            returnType = Order.class, apiSince = 3)
    public List<Order> findByPriority(String priority) {
        return List.of();
    }

    private Order buildStubOrder(Long id) {
        Order order = new Order();
        order.setId(id);
        order.setStatus(Order.OrderStatus.PENDING);
        order.setTotalAmountCents(9999L);
        order.setTotalMajorUnits(99);
        order.setTotalMinorUnits(99);
        order.setItemCount(3);
        order.setLegacyNotes("Legacy reference note");

        OrderAction created = new OrderAction();
        created.setId(100L);
        created.setActionType(OrderAction.ActionType.CREATED);
        created.setDescription("Order placed");
        created.setTimestamp("2026-03-01T10:00:00Z");
        created.setOrder(order);

        OrderAction confirmed = new OrderAction();
        confirmed.setId(101L);
        confirmed.setActionType(OrderAction.ActionType.CONFIRMED);
        confirmed.setDescription("Payment confirmed");
        confirmed.setTimestamp("2026-03-01T10:05:00Z");
        confirmed.setOrder(order);

        order.setActions(List.of(created, confirmed));
        return order;
    }
}
