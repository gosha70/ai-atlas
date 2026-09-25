package shop.generated;

import java.lang.Long;
import java.lang.String;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.Generated;
import shop.Legacy;

@Generated("com.egoge.ai.atlas.processor")
public record LegacyDto(Long id) {
    public static final String CLASS_NAME = "Legacy";

    public static final String CLASS_DESCRIPTION = "Only exists in v1";

    public static final boolean INCLUDE_TYPE_INFO = true;

    public static final Map<String, FieldMeta> FIELD_METADATA = Map.ofEntries(
    Map.entry("id", new FieldMeta("Legacy identifier", List.of(), false, true, false, ""))
    );

    public static LegacyDto fromEntity(Legacy entity) {
        if (entity == null) return null;
        return new LegacyDto(
                entity.getId()
                );
    }

    public static record FieldMeta(String description, List<String> validValues, boolean sensitive,
            boolean checkCircularReference, boolean deprecated, String deprecatedMessage) {
    }
}
