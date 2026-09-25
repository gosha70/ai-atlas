package shop.generated;

import java.lang.Double;
import java.lang.Long;
import java.lang.Object;
import java.lang.String;
import java.lang.ThreadLocal;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import javax.annotation.processing.Generated;
import shop.OrderLine;
import shop.Product;
import shop.Status;

@Generated("com.egoge.ai.atlas.processor")
public record ProductView(Long id, String name, Double cost, String newCode, Status status,
        String size, List<String> tags, Set<Status> statuses, List<OrderLineDto> lines,
        List<OrderLineDto> lineArray, List<OrderLineDto> looseLines,
        List<OrderLineDto> iterableLines, OrderLineDto primaryLine, int stock) {
    public static final String CLASS_NAME = "product";

    public static final String CLASS_DESCRIPTION = "A catalog product";

    public static final boolean INCLUDE_TYPE_INFO = true;

    public static final Map<String, FieldMeta> FIELD_METADATA = Map.ofEntries(
    Map.entry("id", new FieldMeta("Unique identifier", List.of(), false, true, false, "")),
    Map.entry("name", new FieldMeta("Product name", List.of(), false, true, false, "")),
    Map.entry("cost", new FieldMeta("Internal cost", List.of(), true, true, false, "")),
    Map.entry("newCode", new FieldMeta("New code", List.of(), false, true, false, "")),
    Map.entry("status", new FieldMeta("Lifecycle status", List.of("ACTIVE", "RETIRED"), false, true, true, "Use statuses")),
    Map.entry("sizeCode", new FieldMeta("Size", List.of("S", "M", "L"), false, true, false, "")),
    Map.entry("tags", new FieldMeta("Tags", List.of(), false, true, false, "")),
    Map.entry("statuses", new FieldMeta("All statuses", List.of(), false, true, false, "")),
    Map.entry("lines", new FieldMeta("Order lines", List.of(), false, true, false, "")),
    Map.entry("lineArray", new FieldMeta("Order lines as an array", List.of(), false, true, false, "")),
    Map.entry("looseLines", new FieldMeta("Loosely typed lines", List.of(), false, true, false, "")),
    Map.entry("iterableLines", new FieldMeta("Iterable lines", List.of(), false, true, false, "")),
    Map.entry("primaryLine", new FieldMeta("Primary line", List.of(), false, false, false, "")),
    Map.entry("stock", new FieldMeta("Stock count", List.of(), false, true, false, ""))
    );

    private static final ThreadLocal<Set<Object>> _visiting = new ThreadLocal<>();

    public static ProductView fromEntity(Product entity) {
        if (entity == null) return null;
        Set<Object> v = _visiting.get();
        boolean root = v == null;
        if (root) {
            v = Collections.newSetFromMap(new IdentityHashMap<>());
            _visiting.set(v);
        }
        if (!v.add(entity)) return null;
        try {
            return new ProductView(
                    entity.getId(),
                    entity.getName(),
                    entity.getCost(),
                    entity.getNewCode(),
                    entity.getStatus(),
                    entity.getSize(),
                    entity.getTags(),
                    entity.getStatuses(),
                    entity.getLines() == null ? null : entity.getLines().stream().map(e -> OrderLineDto.fromEntity((OrderLine) e)).toList(),
                    entity.getLineArray() == null ? null : Arrays.stream(entity.getLineArray()).map(e -> OrderLineDto.fromEntity((OrderLine) e)).toList(),
                    entity.getLooseLines() == null ? null : entity.getLooseLines().stream().map(e -> OrderLineDto.fromEntity((OrderLine) e)).toList(),
                    entity.getIterableLines() == null ? null : StreamSupport.stream(entity.getIterableLines().spliterator(), false).map(e -> OrderLineDto.fromEntity((OrderLine) e)).toList(),
                    OrderLineDto.fromEntity(entity.getPrimaryLine()),
                    entity.getStock()
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
