package shop.generated;

import java.lang.Long;
import java.lang.String;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import shop.AdminService;
import shop.Customer;
import shop.Status;
import shop.api.CustomerDto;

@Generated("com.egoge.ai.atlas.processor")
@RestController
@RequestMapping("/api/v1/admin-service")
public class AdminServiceRestController {
    private final AdminService service;

    public AdminServiceRestController(AdminService service) {
        this.service = service;
    }

    @GetMapping("/refresh")
    public void refresh() {
        service.refresh();
    }

    @PostMapping("/count")
    public long count(@RequestParam Status status) {
        return service.count(status);
    }

    @GetMapping("/customers")
    public List<CustomerDto> customers() {
        return service.customers().stream().map(e -> CustomerDto.fromEntity((Customer) e)).toList();
    }

    @PostMapping("/label")
    public String label(@RequestParam Long id, @RequestParam boolean upper) {
        return service.label(id, upper);
    }
}
