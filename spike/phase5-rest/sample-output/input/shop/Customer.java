package shop;
import com.egoge.ai.atlas.annotations.*;
@AgenticEntity(description = "A customer")
public class Customer {
    @AgenticField(description = "Customer id") private Long id;
    @AgenticField(description = "Name") private String name;
    public Customer() { }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
