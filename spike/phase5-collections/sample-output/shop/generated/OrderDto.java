package shop.generated;

import java.lang.Long;
import java.lang.String;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.Generated;
import shop.Order;

@Generated("com.egoge.ai.atlas.processor")
public record OrderDto(Long id, String status) {
    public static final String CLASS_NAME = "Order";

    public static final String CLASS_DESCRIPTION = "A customer order";

    public static final boolean INCLUDE_TYPE_INFO = true;

    public static final Map<String, FieldMeta> FIELD_METADATA = Map.ofEntries(
    Map.entry("id", new FieldMeta("Order id", List.of(), false, true, false, "")),
    Map.entry("status", new FieldMeta("Status", List.of(), false, true, false, ""))
    );

    public static OrderDto fromEntity(Order entity) {
        if (entity == null) return null;
        return new OrderDto(
                entity.getId(),
                entity.getStatus()
                );
    }

    public static record FieldMeta(String description, List<String> validValues, boolean sensitive,
            boolean checkCircularReference, boolean deprecated, String deprecatedMessage) {
    }
}
