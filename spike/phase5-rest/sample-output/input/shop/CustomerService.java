package shop;
import com.egoge.ai.atlas.annotations.*;
import com.egoge.ai.atlas.annotations.AgenticExposed.*;
import java.util.*;
@AgenticExposed(description = "Customers", returnType = Customer.class,
        rest = @Rest(style = RestStyle.CRUD, resource = "customers"))
public class CustomerService {
    private final Map<Long, Customer> store = new TreeMap<>();
    public List<Customer> findAll() { return new ArrayList<>(store.values()); }
    public Customer findById(Long id) { return store.get(id); }
    public Customer create(Customer customer) { store.put(customer.getId(), customer); return customer; }
    public Customer update(Long id, Customer customer) { customer.setId(id); store.put(id, customer); return customer; }
    public void deleteById(Long id) { store.remove(id); }
    @AgenticExposed(description = "Customer by name",
            rest = @Rest(method = HttpMethod.GET, path = "/by-name/{name}"))
    public Customer findByName(String name) {
        return store.values().stream().filter(c -> c.getName().equals(name)).findFirst().orElse(null);
    }
    public Customer activate(Long id) { return store.get(id); }
}
