package shop;

import com.egoge.ai.atlas.annotations.AgenticEntity;
import com.egoge.ai.atlas.annotations.AgenticField;

@AgenticEntity(description = "A line on an order")
public class OrderLine {
    @AgenticField(description = "Line identifier")
    private Long id;

    @AgenticField(description = "Free-text note", sinceVersion = 2)
    private String note;

    public Long getId() { return id; }
    public String getNote() { return note; }
}
