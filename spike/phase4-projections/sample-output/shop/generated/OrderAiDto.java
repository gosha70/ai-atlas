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
import shop.Order;
import shop.OrderAction;

@Generated("com.egoge.ai.atlas.processor")
public record OrderAiDto(Long id, String status, String agentSummary,
        List<OrderActionAiDto> actions, CustomerDto customer) {
    public static final String CLASS_NAME = "Order";

    public static final String CLASS_DESCRIPTION = "A customer order";

    public static final boolean INCLUDE_TYPE_INFO = true;

    public static final Map<String, FieldMeta> FIELD_METADATA = Map.ofEntries(
    Map.entry("id", new FieldMeta("Order id", List.of(), false, true, false, "")),
    Map.entry("status", new FieldMeta("Status", List.of(), false, true, false, "")),
    Map.entry("agentSummary", new FieldMeta("Plain-language summary for agents", List.of(), false, true, false, "")),
    Map.entry("actions", new FieldMeta("Actions on the order", List.of(), false, true, false, "")),
    Map.entry("customer", new FieldMeta("The customer", List.of(), false, true, false, ""))
    );

    private static final ThreadLocal<Set<Object>> _visiting = new ThreadLocal<>();

    public static OrderAiDto fromEntity(Order entity) {
        if (entity == null) return null;
        Set<Object> v = _visiting.get();
        boolean root = v == null;
        if (root) {
            v = Collections.newSetFromMap(new IdentityHashMap<>());
            _visiting.set(v);
        }
        if (!v.add(entity)) return null;
        try {
            return new OrderAiDto(
                    entity.getId(),
                    entity.getStatus(),
                    entity.getAgentSummary(),
                    entity.getActions() == null ? null : entity.getActions().stream().map(e -> OrderActionAiDto.fromEntity((OrderAction) e)).toList(),
                    CustomerDto.fromEntity(entity.getCustomer())
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
