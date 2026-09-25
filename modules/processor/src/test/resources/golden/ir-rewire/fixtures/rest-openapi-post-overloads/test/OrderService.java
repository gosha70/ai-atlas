package test;
import com.egoge.ai.atlas.annotations.AgenticExposed;
@AgenticExposed(description = "Orders", channels = { AgenticExposed.Channel.API })
public class OrderService {
    public String find(Long id) { return null; }
    public String find(String code) { return null; }
}
