package com.egoge.ai.atlas.demo.entity.generated;

import com.egoge.ai.atlas.demo.entity.Address;
import com.egoge.ai.atlas.demo.entity.Customer;
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

@Generated("com.egoge.ai.atlas.processor")
public record CustomerDto(Long id, String name, List<AddressDto> addresses) {
    public static final String CLASS_NAME = "customer";

    public static final String CLASS_DESCRIPTION = "A customer with addresses and contact info";

    public static final boolean INCLUDE_TYPE_INFO = true;

    public static final Map<String, FieldMeta> FIELD_METADATA = Map.ofEntries(
    Map.entry("id", new FieldMeta("Unique customer identifier", List.of(), false, true, false, "")),
    Map.entry("name", new FieldMeta("Customer display name", List.of(), false, true, false, "")),
    Map.entry("addresses", new FieldMeta("Customer mailing addresses", List.of(), false, true, false, ""))
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
                    entity.getName(),
                    entity.getAddresses() == null ? null : entity.getAddresses().stream().map(e -> AddressDto.fromEntity((Address) e)).toList()
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
