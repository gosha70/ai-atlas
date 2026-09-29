package shop;
import com.egoge.ai.atlas.annotations.*;
import com.egoge.ai.atlas.annotations.AgenticParam.Paging;
import org.springframework.data.domain.*;
import java.util.List;
@AgenticExposed(description = "Order operations", returnType = Order.class)
public class OrderService {
    /** The last Pageable the service received, to prove what the wrappers pass. */
    public static Pageable lastPageable;
    @AgenticExposed(description = "Orders in a status, a page at a time")
    public Page<Order> byStatus(String status, Pageable pageable) {
        lastPageable = pageable;
        List<Order> all = Order.all();
        int from = (int) Math.min(pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return new PageImpl<>(all.subList(from, to), pageable, all.size());
    }
    @AgenticExposed(description = "Recent orders, a slice at a time")
    public Slice<Order> recent(Pageable pageable) {
        lastPageable = pageable;
        List<Order> all = Order.all();
        int from = (int) Math.min(pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return new SliceImpl<>(all.subList(from, to), pageable, to < all.size());
    }
    @AgenticExposed(description = "Every order")
    public List<Order> list() { return Order.all(); }
    @AgenticExposed(description = "The top orders", maxResults = 2)
    public List<Order> top() { return Order.all(); }
    @AgenticExposed(description = "Search orders")
    public List<Order> search(String text, @AgenticParam(paging = Paging.LIMIT) int limit,
                              @AgenticParam(paging = Paging.CURSOR) String after) {
        return Order.all().subList(0, Math.min(limit, 5));
    }
    @AgenticExposed(description = "Orders after a cursor")
    public List<Order> after(@AgenticParam(paging = Paging.CURSOR) String cursor) { return Order.all(); }
    @AgenticExposed(description = "One order")
    public Order find(Long id) { return Order.all().get(0); }
}
