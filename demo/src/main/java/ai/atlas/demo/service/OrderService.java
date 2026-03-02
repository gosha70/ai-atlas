/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.atlas.demo.service;

import ai.atlas.annotations.AgenticExposed;
import ai.atlas.demo.entity.Order;
import ai.atlas.demo.entity.OrderAction;
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

    public Order findById(Long id) {
        // Stub — will be backed by real data in Cycle 2
        Order order = new Order();
        order.setId(id);
        order.setStatus(Order.OrderStatus.PENDING);
        order.setTotalAmountCents(9999L);
        order.setItemCount(3);

        // Bidirectional: each action points back to this order
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

    public List<Order> findByStatus(String status) {
        // Stub
        return List.of();
    }
}
