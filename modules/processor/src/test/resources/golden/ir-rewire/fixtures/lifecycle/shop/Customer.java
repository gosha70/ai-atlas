package shop;

import com.egoge.ai.atlas.annotations.AgenticEntity;
import com.egoge.ai.atlas.annotations.AgenticField;

@AgenticEntity(packageName = "shop.api", description = "A customer")
public class Customer {
    @AgenticField(description = "Customer identifier")
    private Long id;

    @AgenticField(description = "Favourite product", removedInVersion = 3)
    private Product favourite;

    public Long getId() { return id; }
    public Product getFavourite() { return favourite; }
}
