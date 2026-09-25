package com.egoge.ai.atlas.demo.service.generated;

import com.egoge.ai.atlas.demo.entity.Customer;
import com.egoge.ai.atlas.demo.entity.generated.CustomerDto;
import com.egoge.ai.atlas.demo.service.CustomerService;
import java.lang.Deprecated;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Generated("com.egoge.ai.atlas.processor")
@RestController
@RequestMapping("/api/v2/customer-service")
public class CustomerServiceRestController {
    private final CustomerService service;

    public CustomerServiceRestController(CustomerService service) {
        this.service = service;
    }

    @GetMapping("/get-customers")
    @Deprecated
    public List<CustomerDto> getCustomers() {
        return service.getCustomers().stream().map(e -> CustomerDto.fromEntity((Customer) e)).toList();
    }

    @GetMapping("/get-customers-v2")
    public List<CustomerDto> getCustomersV2() {
        return service.getCustomersV2().stream().map(e -> CustomerDto.fromEntity((Customer) e)).toList();
    }
}
