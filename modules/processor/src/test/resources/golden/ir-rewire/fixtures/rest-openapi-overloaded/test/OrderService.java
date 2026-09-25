package test;
import com.egoge.ai.atlas.annotations.AgenticExposed;
@AgenticExposed(description = "Orders", channels = { AgenticExposed.Channel.API })
public class OrderService {
    public String find() { return null; }
    public String find(Long id) { return null; }
}
