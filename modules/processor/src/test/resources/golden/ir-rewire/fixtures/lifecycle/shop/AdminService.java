package shop;

import com.egoge.ai.atlas.annotations.AgenticExposed;
import java.util.List;

public class AdminService {
    @AgenticExposed(description = "Refresh caches")
    public void refresh() { }

    @AgenticExposed(description = "Count products", toolName = "countProducts")
    public long count(Status status) { return 0; }

    @AgenticExposed(description = "All customers", returnType = Customer.class)
    public List<?> customers() { return List.of(); }

    @AgenticExposed(description = "Product label", channels = { AgenticExposed.Channel.API })
    public String label(Long id, boolean upper) { return null; }

    public String notExposed() { return null; }
}
