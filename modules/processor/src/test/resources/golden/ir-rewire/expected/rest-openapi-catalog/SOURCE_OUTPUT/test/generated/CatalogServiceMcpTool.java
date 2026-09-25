package test.generated;

import java.lang.Boolean;
import java.lang.Integer;
import java.lang.Long;
import java.lang.Object;
import java.lang.String;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.Generated;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import test.CatalogService;
import test.Item;

@Generated("com.egoge.ai.atlas.processor")
@Service
public class CatalogServiceMcpTool {
    private final CatalogService service;

    public CatalogServiceMcpTool(CatalogService service) {
        this.service = service;
    }

    @Tool(
            name = "current",
            description = "Current item"
    )
    public ItemDto current() {
        return ItemDto.fromEntity(service.current());
    }

    @Tool(
            name = "findById",
            description = "Item by id"
    )
    public ItemDto findById(@ToolParam(description = "id") Long id) {
        return ItemDto.fromEntity(service.findById(id));
    }

    @Tool(
            name = "search",
            description = "Search items"
    )
    public List<ItemDto> search(@ToolParam(description = "name") String name,
            @ToolParam(description = "limit") Integer limit,
            @ToolParam(description = "active") Boolean active) {
        return service.search(name, limit, active).stream().map(e -> ItemDto.fromEntity((Item) e)).toList();
    }

    @Tool(
            name = "label",
            description = "Item label"
    )
    public String label(@ToolParam(description = "id") Long id) {
        return service.label(id);
    }

    @Tool(
            name = "count",
            description = "Item count"
    )
    public long count() {
        return service.count();
    }

    @Tool(
            name = "ids",
            description = "Item ids"
    )
    public List<Long> ids() {
        return service.ids();
    }

    @Tool(
            name = "stats",
            description = "Item stats"
    )
    public Map<String, Object> stats() {
        return service.stats();
    }

    @Tool(
            name = "touch",
            description = "Touch item"
    )
    public void touch(@ToolParam(description = "id") Long id) {
        service.touch(id);
    }
}
