package com.egoge.ai.atlas.demo.entity.generated;

import com.egoge.ai.atlas.demo.entity.Order;
import com.egoge.ai.atlas.demo.entity.OrderAction;
import java.lang.Long;
import java.lang.Object;
import java.lang.String;
import java.lang.ThreadLocal;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("com.egoge.ai.atlas.processor")
public record OrderDto(Long id, Order.OrderStatus status, long totalAmountCents,
        int totalMajorUnits, int totalMinorUnits, int itemCount, List<OrderActionDto> actions) {
    public static final String CLASS_NAME = "order";

    public static final String CLASS_DESCRIPTION = "A customer order with status tracking and item summary";

    public static final boolean INCLUDE_TYPE_INFO = true;

    public static final Map<String, FieldMeta> FIELD_METADATA = Map.ofEntries(
    Map.entry("id", new FieldMeta("Unique order identifier", List.of(), false, true, false, "")),
    Map.entry("status", new FieldMeta("Current order status", List.of("PENDING", "CONFIRMED", "SHIPPED", "DELIVERED", "CANCELLED"), false, true, false, "")),
    Map.entry("totalCents", new FieldMeta("Total order amount in cents", List.of(), false, true, true, "Use totalMajorUnits and totalMinorUnits instead")),
    Map.entry("totalMajorUnits", new FieldMeta("Total amount — whole currency units (e.g. dollars)", List.of(), false, true, false, "")),
    Map.entry("totalMinorUnits", new FieldMeta("Total amount — fractional currency units (e.g. cents)", List.of(), false, true, false, "")),
    Map.entry("itemCount", new FieldMeta("Number of items in the order", List.of(), false, true, false, "")),
    Map.entry("actions", new FieldMeta("Actions performed on this order", List.of(), false, true, false, ""))
    );

    private static final ThreadLocal<Set<Object>> _visiting = new ThreadLocal<>();

    public static OrderDto fromEntity(Order entity) {
        if (entity == null) return null;
        Set<Object> v = _visiting.get();
        boolean root = v == null;
        if (root) {
            v = Collections.newSetFromMap(new IdentityHashMap<>());
            _visiting.set(v);
        }
        if (!v.add(entity)) return null;
        try {
            return new OrderDto(
                    entity.getId(),
                    entity.getStatus(),
                    entity.getTotalAmountCents(),
                    entity.getTotalMajorUnits(),
                    entity.getTotalMinorUnits(),
                    entity.getItemCount(),
                    entity.getActions() == null ? null : entity.getActions().stream().map(e -> OrderActionDto.fromEntity((OrderAction) e)).toList()
                    );
        } finally {
            v.remove(entity);
            if (root) {
                _visiting.remove();
            }
        }
    }

    public static record FieldMeta(String description, List<String> validValues, boolean sensitive,
            boolean checkCircularReference, boolean deprecated, String deprecatedMessage) {
    }
}
