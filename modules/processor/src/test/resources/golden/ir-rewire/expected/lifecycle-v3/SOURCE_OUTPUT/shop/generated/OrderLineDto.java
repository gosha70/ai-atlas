package shop.generated;

import java.lang.Long;
import java.lang.String;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.Generated;
import shop.OrderLine;

@Generated("com.egoge.ai.atlas.processor")
public record OrderLineDto(Long id, String note) {
    public static final String CLASS_NAME = "OrderLine";

    public static final String CLASS_DESCRIPTION = "A line on an order";

    public static final boolean INCLUDE_TYPE_INFO = true;

    public static final Map<String, FieldMeta> FIELD_METADATA = Map.ofEntries(
    Map.entry("id", new FieldMeta("Line identifier", List.of(), false, true, false, "")),
    Map.entry("note", new FieldMeta("Free-text note", List.of(), false, true, false, ""))
    );

    public static OrderLineDto fromEntity(OrderLine entity) {
        if (entity == null) return null;
        return new OrderLineDto(
                entity.getId(),
                entity.getNote()
                );
    }

    public static record FieldMeta(String description, List<String> validValues, boolean sensitive,
            boolean checkCircularReference, boolean deprecated, String deprecatedMessage) {
    }
}
