package shop.generated;

import java.lang.Long;
import java.lang.String;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import shop.Customer;
import shop.CustomerService;

@Generated("com.egoge.ai.atlas.processor")
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerServiceRestController {
    private final CustomerService service;

    public CustomerServiceRestController(CustomerService service) {
        this.service = service;
    }

    @GetMapping
    public List<CustomerDto> findAll() {
        return service.findAll().stream().map(e -> CustomerDto.fromEntity((Customer) e)).toList();
    }

    @GetMapping("/{id}")
    public CustomerDto findById(@PathVariable("id") Long id) {
        return CustomerDto.fromEntity(service.findById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerDto create(@RequestBody Customer customer) {
        return CustomerDto.fromEntity(service.create(customer));
    }

    @PutMapping("/{id}")
    public CustomerDto update(@PathVariable("id") Long id, @RequestBody Customer customer) {
        return CustomerDto.fromEntity(service.update(id, customer));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteById(@PathVariable("id") Long id) {
        service.deleteById(id);
    }

    @GetMapping("/by-name/{name}")
    public CustomerDto findByName(@PathVariable("name") String name) {
        return CustomerDto.fromEntity(service.findByName(name));
    }

    @PostMapping("/activate")
    public CustomerDto activate(@RequestParam Long id) {
        return CustomerDto.fromEntity(service.activate(id));
    }
}
