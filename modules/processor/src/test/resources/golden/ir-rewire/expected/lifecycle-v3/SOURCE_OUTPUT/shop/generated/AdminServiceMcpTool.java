package shop.generated;

import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import shop.AdminService;
import shop.Customer;
import shop.Status;
import shop.api.CustomerDto;

@Generated("com.egoge.ai.atlas.processor")
@Service
public class AdminServiceMcpTool {
    private final AdminService service;

    public AdminServiceMcpTool(AdminService service) {
        this.service = service;
    }

    @Tool(
            name = "refresh",
            description = "Refresh caches"
    )
    public void refresh() {
        service.refresh();
    }

    @Tool(
            name = "countProducts",
            description = "Count products"
    )
    public long countProducts(@ToolParam(description = "status") Status status) {
        return service.count(status);
    }

    @Tool(
            name = "customers",
            description = "All customers"
    )
    public List<CustomerDto> customers() {
        return service.customers().stream().map(e -> CustomerDto.fromEntity((Customer) e)).toList();
    }
}
