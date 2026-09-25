package shop.api;

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
import shop.Customer;
import shop.generated.ProductView;

@Generated("com.egoge.ai.atlas.processor")
public record CustomerDto(Long id, ProductView favourite) {
    public static final String CLASS_NAME = "Customer";

    public static final String CLASS_DESCRIPTION = "A customer";

    public static final boolean INCLUDE_TYPE_INFO = true;

    public static final Map<String, FieldMeta> FIELD_METADATA = Map.ofEntries(
    Map.entry("id", new FieldMeta("Customer identifier", List.of(), false, true, false, "")),
    Map.entry("favourite", new FieldMeta("Favourite product", List.of(), false, true, false, ""))
    );

    private static final ThreadLocal<Set<Object>> _visiting = new ThreadLocal<>();

    public static CustomerDto fromEntity(Customer entity) {
        if (entity == null) return null;
        Set<Object> v = _visiting.get();
        boolean root = v == null;
        if (root) {
            v = Collections.newSetFromMap(new IdentityHashMap<>());
            _visiting.set(v);
        }
        if (!v.add(entity)) return null;
        try {
            return new CustomerDto(
                    entity.getId(),
                    ProductView.fromEntity(entity.getFavourite())
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
