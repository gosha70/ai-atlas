package shop.api;

import java.lang.Long;
import java.lang.String;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.Generated;
import shop.Customer;

@Generated("com.egoge.ai.atlas.processor")
public record CustomerDto(Long id) {
    public static final String CLASS_NAME = "Customer";

    public static final String CLASS_DESCRIPTION = "A customer";

    public static final boolean INCLUDE_TYPE_INFO = true;

    public static final Map<String, FieldMeta> FIELD_METADATA = Map.ofEntries(
    Map.entry("id", new FieldMeta("Customer identifier", List.of(), false, true, false, ""))
    );

    public static CustomerDto fromEntity(Customer entity) {
        if (entity == null) return null;
        return new CustomerDto(
                entity.getId()
                );
    }

    public static record FieldMeta(String description, List<String> validValues, boolean sensitive,
            boolean checkCircularReference, boolean deprecated, String deprecatedMessage) {
    }
}
