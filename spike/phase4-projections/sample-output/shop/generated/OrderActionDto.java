package shop.generated;

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
import shop.OrderAction;

@Generated("com.egoge.ai.atlas.processor")
public record OrderActionDto(Long id, String type, String performedBy, OrderDto order) {
    public static final String CLASS_NAME = "OrderAction";

    public static final String CLASS_DESCRIPTION = "An action on an order";

    public static final boolean INCLUDE_TYPE_INFO = true;

    public static final Map<String, FieldMeta> FIELD_METADATA = Map.ofEntries(
    Map.entry("id", new FieldMeta("Action id", List.of(), false, true, false, "")),
    Map.entry("type", new FieldMeta("Action type", List.of(), false, true, false, "")),
    Map.entry("performedBy", new FieldMeta("Employee who performed it", List.of(), false, true, false, "")),
    Map.entry("order", new FieldMeta("Parent order", List.of(), false, true, false, ""))
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
                    entity.getType(),
                    entity.getPerformedBy(),
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
