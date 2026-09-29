package shop.generated;

import java.lang.Long;
import java.lang.String;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import shop.Customer;
import shop.CustomerService;

@Generated("com.egoge.ai.atlas.processor")
@Service
public class CustomerServiceMcpTool {
    private final CustomerService service;

    public CustomerServiceMcpTool(CustomerService service) {
        this.service = service;
    }

    @Tool(
            name = "findAll",
            description = "Customers"
    )
    public List<CustomerDto> findAll() {
        return service.findAll().stream().map(e -> CustomerDto.fromEntity((Customer) e)).toList();
    }

    @Tool(
            name = "findById",
            description = "Customers"
    )
    public CustomerDto findById(@ToolParam(description = "id") Long id) {
        return CustomerDto.fromEntity(service.findById(id));
    }

    @Tool(
            name = "create",
            description = "Customers"
    )
    public CustomerDto create(@ToolParam(description = "customer") Customer customer) {
        return CustomerDto.fromEntity(service.create(customer));
    }

    @Tool(
            name = "update",
            description = "Customers"
    )
    public CustomerDto update(@ToolParam(description = "id") Long id,
            @ToolParam(description = "customer") Customer customer) {
        return CustomerDto.fromEntity(service.update(id, customer));
    }

    @Tool(
            name = "deleteById",
            description = "Customers"
    )
    public void deleteById(@ToolParam(description = "id") Long id) {
        service.deleteById(id);
    }

    @Tool(
            name = "findByName",
            description = "Customer by name"
    )
    public CustomerDto findByName(@ToolParam(description = "name") String name) {
        return CustomerDto.fromEntity(service.findByName(name));
    }

    @Tool(
            name = "activate",
            description = "Customers"
    )
    public CustomerDto activate(@ToolParam(description = "id") Long id) {
        return CustomerDto.fromEntity(service.activate(id));
    }
}
