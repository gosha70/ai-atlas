package shop;
import com.egoge.ai.atlas.annotations.*;
@AgenticEntity(description = "A customer")
public class Customer {
    @AgenticField(description = "Customer id") private Long id;
    @AgenticField(description = "Display name") private String name;
    public Customer(Long id, String name) { this.id = id; this.name = name; }
    public Long getId() { return id; }
    public String getName() { return name; }
}
