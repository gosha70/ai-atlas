package a;

import com.egoge.ai.atlas.annotations.AgenticExposed;

public class InventoryService {
    @AgenticExposed(description = "Find inventory", channels = { AgenticExposed.Channel.API })
    public String find() { return null; }
}
