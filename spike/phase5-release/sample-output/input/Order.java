package test;

import com.egoge.ai.atlas.annotations.AgenticEntity;
import com.egoge.ai.atlas.annotations.AgenticField;

@AgenticEntity(description = "An order")
public class Order {
    @AgenticField(description = "Id") private Long id;
    @AgenticField(description = "A note") private String note;
    @AgenticField(description = "Legacy code", deprecatedSinceVersion = 1, removedInVersion = 2, deprecatedMessage = "Use id") private String legacy;

    public Long getId() { return id; }
    public String getNote() { return null; }
    public String getLegacy() { return null; }
}
