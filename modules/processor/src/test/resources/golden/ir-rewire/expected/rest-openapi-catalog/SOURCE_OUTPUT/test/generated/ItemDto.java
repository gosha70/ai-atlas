package test.generated;

import java.lang.Long;
import java.lang.String;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.Generated;
import test.Item;

@Generated("com.egoge.ai.atlas.processor")
public record ItemDto(Long id) {
    public static final String CLASS_NAME = "Item";

    public static final String CLASS_DESCRIPTION = "";

    public static final boolean INCLUDE_TYPE_INFO = true;

    public static final Map<String, FieldMeta> FIELD_METADATA = Map.ofEntries(
    Map.entry("id", new FieldMeta("ID", List.of(), false, true, false, ""))
    );

    public static ItemDto fromEntity(Item entity) {
        if (entity == null) return null;
        return new ItemDto(
                entity.getId()
                );
    }

    public static record FieldMeta(String description, List<String> validValues, boolean sensitive,
            boolean checkCircularReference, boolean deprecated, String deprecatedMessage) {
    }
}
