package shop;
import com.egoge.ai.atlas.annotations.*;
@AgenticEntity(description = "An order")
public class Order {
    @AgenticField(description = "Order id") private Long id;
    @AgenticField(description = "Status") private String status;
    private String ssn;
    public Order() { }
    public Order(Long id, String status) { this.id = id; this.status = status; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSsn() { return ssn; }
    public void setSsn(String ssn) { this.ssn = ssn; }
}
