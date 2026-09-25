package shop;

import com.egoge.ai.atlas.annotations.AgenticEntity;
import com.egoge.ai.atlas.annotations.AgenticField;
import java.util.List;
import java.util.Set;

@AgenticEntity(name = "product", dtoName = "ProductView", description = "A catalog product",
        includeTypeInfo = true)
public class Product extends BaseEntity {
    @AgenticField(description = "Product name")
    private String name;

    @AgenticField(description = "Internal cost", sensitive = true)
    private Double cost;

    @AgenticField(description = "Legacy code", removedInVersion = 2)
    private String legacyCode;

    @AgenticField(description = "New code", sinceVersion = 3)
    private String newCode;

    @AgenticField(description = "Lifecycle status", deprecatedSinceVersion = 2,
            deprecatedMessage = "Use statuses")
    private Status status;

    @AgenticField(name = "sizeCode", description = "Size", allowedValues = {"S", "M", "L"})
    private String size;

    @AgenticField(description = "Tags")
    private List<String> tags;

    @AgenticField(description = "All statuses")
    private Set<Status> statuses;

    @AgenticField(description = "Order lines")
    private List<OrderLine> lines;

    @AgenticField(description = "Order lines as an array")
    private OrderLine[] lineArray;

    @AgenticField(description = "Loosely typed lines", type = OrderLine.class)
    private List<?> looseLines;

    @AgenticField(description = "Iterable lines")
    private Iterable<OrderLine> iterableLines;

    @AgenticField(description = "Primary line", checkCircularReference = false)
    private OrderLine primaryLine;

    @AgenticField(description = "Stock count")
    private int stock;

    public String getName() { return name; }
    public Double getCost() { return cost; }
    public String getLegacyCode() { return legacyCode; }
    public String getNewCode() { return newCode; }
    public Status getStatus() { return status; }
    public String getSize() { return size; }
    public List<String> getTags() { return tags; }
    public Set<Status> getStatuses() { return statuses; }
    public List<OrderLine> getLines() { return lines; }
    public OrderLine[] getLineArray() { return lineArray; }
    public List<?> getLooseLines() { return looseLines; }
    public Iterable<OrderLine> getIterableLines() { return iterableLines; }
    public OrderLine getPrimaryLine() { return primaryLine; }
    public int getStock() { return stock; }
}
