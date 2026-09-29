package shop;
import com.egoge.ai.atlas.annotations.*;
import java.util.List;
import java.util.stream.LongStream;
@AgenticEntity(description = "A customer order")
public class Order {
    @AgenticField(description = "Order id") private Long id;
    @AgenticField(description = "Status") private String status;
    public Order(Long id, String status) { this.id = id; this.status = status; }
    public Long getId() { return id; }
    public String getStatus() { return status; }
    /** Five orders: every result set of the fixture. */
    public static List<Order> all() {
        return LongStream.rangeClosed(1, 5).mapToObj(i -> new Order(i, "NEW")).toList();
    }
}
