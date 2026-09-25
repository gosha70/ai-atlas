package com.egoge.ai.atlas.demo.entity.generated;

import com.egoge.ai.atlas.demo.entity.Address;
import java.lang.Long;
import java.lang.String;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.Generated;

@Generated("com.egoge.ai.atlas.processor")
public record AddressDto(Long id, String street, String city, String state, String zipCode,
        String country) {
    public static final String CLASS_NAME = "address";

    public static final String CLASS_DESCRIPTION = "A customer mailing address";

    public static final boolean INCLUDE_TYPE_INFO = true;

    public static final Map<String, FieldMeta> FIELD_METADATA = Map.ofEntries(
    Map.entry("id", new FieldMeta("Unique address identifier", List.of(), false, true, false, "")),
    Map.entry("street", new FieldMeta("Street line", List.of(), false, true, false, "")),
    Map.entry("city", new FieldMeta("City name", List.of(), false, true, false, "")),
    Map.entry("state", new FieldMeta("State or province code", List.of(), false, true, false, "")),
    Map.entry("zipCode", new FieldMeta("Postal / ZIP code", List.of(), false, true, false, "")),
    Map.entry("country", new FieldMeta("ISO 3166-1 country code", List.of(), false, true, false, ""))
    );

    public static AddressDto fromEntity(Address entity) {
        if (entity == null) return null;
        return new AddressDto(
                entity.getId(),
                entity.getStreet(),
                entity.getCity(),
                entity.getState(),
                entity.getZipCode(),
                entity.getCountry()
                );
    }

    public static record FieldMeta(String description, List<String> validValues, boolean sensitive,
            boolean checkCircularReference, boolean deprecated, String deprecatedMessage) {
    }
}
