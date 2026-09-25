package test;
import com.egoge.ai.atlas.annotations.*;
@AgenticEntity public class Item {
    @AgenticField(description = "ID") private Long id;
    public Long getId() { return id; }
}
