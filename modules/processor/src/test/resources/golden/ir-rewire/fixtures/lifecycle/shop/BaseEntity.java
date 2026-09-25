package shop;

import com.egoge.ai.atlas.annotations.AgenticField;

public abstract class BaseEntity {
    @AgenticField(description = "Unique identifier")
    private Long id;

    public Long getId() { return id; }
}
