package shop;

import com.egoge.ai.atlas.annotations.AgenticEntity;
import com.egoge.ai.atlas.annotations.AgenticField;

@AgenticEntity(description = "Only exists in v1")
public class Legacy {
    @AgenticField(description = "Legacy identifier", removedInVersion = 2)
    private Long id;

    public Long getId() { return id; }
}
