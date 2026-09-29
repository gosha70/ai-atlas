package shop.generated;

import java.lang.Long;
import java.lang.String;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import shop.Order;
import shop.OrderService;

@Generated("com.egoge.ai.atlas.processor")
@RestController
@RequestMapping("/api/v1/order-service")
public class OrderServiceRestController {
    private final OrderService service;

    public OrderServiceRestController(OrderService service) {
        this.service = service;
    }

    @PostMapping("/by-status")
    public PageResult<OrderDto> byStatus(@RequestParam String status, Pageable pageable) {
        var result = service.byStatus(status, pageable);
        return new PageResult<>(result.getContent().stream().map(e -> OrderDto.fromEntity((Order) e)).toList(), result.getNumber(), result.getSize(), result.hasNext(), result.getTotalElements(), result.getTotalPages());
    }

    @PostMapping("/recent")
    public SliceResult<OrderDto> recent(Pageable pageable) {
        var result = service.recent(pageable);
        return new SliceResult<>(result.getContent().stream().map(e -> OrderDto.fromEntity((Order) e)).toList(), result.getNumber(), result.getSize(), result.hasNext());
    }

    @GetMapping("/list")
    public List<OrderDto> list() {
        return service.list().stream().map(e -> OrderDto.fromEntity((Order) e)).toList();
    }

    @GetMapping("/top")
    public List<OrderDto> top() {
        return service.top().stream().map(e -> OrderDto.fromEntity((Order) e)).toList();
    }

    @PostMapping("/search")
    public List<OrderDto> search(@RequestParam String text, @RequestParam int limit,
            @RequestParam String after) {
        return service.search(text, limit, after).stream().map(e -> OrderDto.fromEntity((Order) e)).toList();
    }

    @PostMapping("/after")
    public List<OrderDto> after(@RequestParam String cursor) {
        return service.after(cursor).stream().map(e -> OrderDto.fromEntity((Order) e)).toList();
    }

    @PostMapping("/find")
    public OrderDto find(@RequestParam Long id) {
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
