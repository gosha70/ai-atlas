package shop.generated;

import jakarta.validation.constraints.NotNull;
import java.lang.Integer;
import java.lang.Long;
import java.lang.String;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import shop.Order;
import shop.OrderService;

@Generated("com.egoge.ai.atlas.processor")
@Service
@Validated
public class OrderServiceMcpTool {
    private final OrderService service;

    public OrderServiceMcpTool(OrderService service) {
        this.service = service;
    }

    @Tool(
            name = "byStatus",
            description = "Orders in a status, a page at a time. Results are paged: pass size (at least 1) and optionally page (zero-based, default 0); the result carries hasNext, totalElements and totalPages."
    )
    public PageResult<OrderDto> byStatus(
            @ToolParam(description = "status", required = true) @NotNull String status,
            @ToolParam(description = "Zero-based page number; 0 when omitted", required = false) Integer page,
            @ToolParam(description = "Page size: the most results to return, at least 1") int size) {
        var result = service.byStatus(status, PageRequest.of(page == null ? 0 : page, size));
        return new PageResult<>(result.getContent().stream().map(e -> OrderDto.fromEntity((Order) e)).toList(), result.getNumber(), result.getSize(), result.hasNext(), result.getTotalElements(), result.getTotalPages());
    }

    @Tool(
            name = "recent",
            description = "Recent orders, a slice at a time. Results are paged: pass size (at least 1) and optionally page (zero-based, default 0); the result carries hasNext."
    )
    public SliceResult<OrderDto> recent(
            @ToolParam(description = "Zero-based page number; 0 when omitted", required = false) Integer page,
            @ToolParam(description = "Page size: the most results to return, at least 1") int size) {
        var result = service.recent(PageRequest.of(page == null ? 0 : page, size));
        return new SliceResult<>(result.getContent().stream().map(e -> OrderDto.fromEntity((Order) e)).toList(), result.getNumber(), result.getSize(), result.hasNext());
    }

    @Tool(
            name = "list",
            description = "Every order"
    )
    public List<OrderDto> list() {
        return service.list().stream().map(e -> OrderDto.fromEntity((Order) e)).toList();
    }

    @Tool(
            name = "top",
            description = "The top orders. Returns at most 2 results."
    )
    public List<OrderDto> top() {
        return service.top().stream().map(e -> OrderDto.fromEntity((Order) e)).toList();
    }

    @Tool(
            name = "search",
            description = "Search orders. Returns at most 'limit' results; pass 'after' to continue after a previous call."
    )
    public List<OrderDto> search(
            @ToolParam(description = "text", required = true) @NotNull String text,
            @ToolParam(description = "limit", required = true) int limit,
            @ToolParam(description = "after", required = true) @NotNull String after) {
        return service.search(text, limit, after).stream().map(e -> OrderDto.fromEntity((Order) e)).toList();
    }

    @Tool(
            name = "after",
            description = "Orders after a cursor"
    )
    public List<OrderDto> after(
            @ToolParam(description = "cursor", required = true) @NotNull String cursor) {
        return service.after(cursor).stream().map(e -> OrderDto.fromEntity((Order) e)).toList();
    }

    @Tool(
            name = "find",
            description = "One order"
    )
    public OrderDto find(@ToolParam(description = "id", required = true) @NotNull Long id) {
        return OrderDto.fromEntity(service.find(id));
    }

    /**
     * A page of results, with the totals the service computed
     */
    @Generated("com.egoge.ai.atlas.processor")
    public record PageResult<T>(List<T> content, int number, int size, boolean hasNext,
            long totalElements, int totalPages) {
    }

    /**
     * A slice of results; hasNext says whether another slice exists
     */
    @Generated("com.egoge.ai.atlas.processor")
    public record SliceResult<T>(List<T> content, int number, int size, boolean hasNext) {
    }
}
