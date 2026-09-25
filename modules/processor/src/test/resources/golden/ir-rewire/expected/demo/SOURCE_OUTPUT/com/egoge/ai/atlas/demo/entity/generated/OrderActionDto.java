package com.egoge.ai.atlas.demo.entity.generated;

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
public record OrderActionDto(Long id, OrderAction.ActionType actionType, String description,
        String timestamp, OrderDto order) {
    public static final String CLASS_NAME = "orderAction";

    public static final String CLASS_DESCRIPTION = "An action or status change performed on an order";

    public static final boolean INCLUDE_TYPE_INFO = true;

    public static final Map<String, FieldMeta> FIELD_METADATA = Map.ofEntries(
    Map.entry("id", new FieldMeta("Unique action identifier", List.of(), false, true, false, "")),
    Map.entry("actionType", new FieldMeta("Type of action performed", List.of("CREATED", "CONFIRMED", "SHIPPED", "DELIVERED", "CANCELLED", "NOTE"), false, true, false, "")),
    Map.entry("description", new FieldMeta("Human-readable action description", List.of(), false, true, false, "")),
    Map.entry("timestamp", new FieldMeta("When the action occurred (ISO-8601)", List.of(), false, true, false, "")),
    Map.entry("order", new FieldMeta("Parent order this action belongs to", List.of(), false, true, false, ""))
    );

    private static final ThreadLocal<Set<Object>> _visiting = new ThreadLocal<>();

    public static OrderActionDto fromEntity(OrderAction entity) {
        if (entity == null) return null;
        Set<Object> v = _visiting.get();
        boolean root = v == null;
        if (root) {
            v = Collections.newSetFromMap(new IdentityHashMap<>());
            _visiting.set(v);
        }
        if (!v.add(entity)) return null;
        try {
            return new OrderActionDto(
                    entity.getId(),
                    entity.getActionType(),
                    entity.getDescription(),
                    entity.getTimestamp(),
                    OrderDto.fromEntity(entity.getOrder())
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
