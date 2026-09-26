package shop.late.generated;

import java.lang.String;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.Generated;
import shop.late.Late;

@Generated("com.egoge.ai.atlas.processor")
public record LateDto(String code) {
    public static final String CLASS_NAME = "Late";

    public static final String CLASS_DESCRIPTION = "";

    public static final boolean INCLUDE_TYPE_INFO = true;

    public static final Map<String, FieldMeta> FIELD_METADATA = Map.ofEntries(
    Map.entry("code", new FieldMeta("", List.of(), false, true, false, ""))
    );

    public static LateDto fromEntity(Late entity) {
        if (entity == null) return null;
        return new LateDto(
                entity.getCode()
                );
    }

    public static record FieldMeta(String description, List<String> validValues, boolean sensitive,
            boolean checkCircularReference, boolean deprecated, String deprecatedMessage) {
    }
}
