package test;

import com.egoge.ai.atlas.annotations.AgenticExposed;

@AgenticExposed(description = "Orders", returnType = Order.class)
public class OrderService {
    @AgenticExposed(description = "Finds an order by id")
    public Order find(Long id) { return null; }
}
