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
import shop.Shipment;

@Generated("com.egoge.ai.atlas.processor")
public record ShipmentAiDto(Long id, List<OrderActionAiDto> actions) {
    public static final String CLASS_NAME = "Shipment";

    public static final String CLASS_DESCRIPTION = "A shipment";

    public static final boolean INCLUDE_TYPE_INFO = true;

    public static final Map<String, FieldMeta> FIELD_METADATA = Map.ofEntries(
    Map.entry("id", new FieldMeta("Shipment id", List.of(), false, true, false, "")),
    Map.entry("actions", new FieldMeta("Actions", List.of(), false, true, false, ""))
    );

    private static final ThreadLocal<Set<Object>> _visiting = new ThreadLocal<>();

    public static ShipmentAiDto fromEntity(Shipment entity) {
        if (entity == null) return null;
        Set<Object> v = _visiting.get();
        boolean root = v == null;
        if (root) {
            v = Collections.newSetFromMap(new IdentityHashMap<>());
            _visiting.set(v);
        }
        if (!v.add(entity)) return null;
        try {
            return new ShipmentAiDto(
                    entity.getId(),
                    entity.getActions() == null ? null : entity.getActions().stream().map(e -> OrderActionAiDto.fromEntity((OrderAction) e)).toList()
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
