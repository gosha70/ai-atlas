package shop;

import com.egoge.ai.atlas.annotations.AgenticExposed;

public class CatalogService {
    @AgenticExposed(description = "List the catalog", channels = { AgenticExposed.Channel.API })
    public String list() { return null; }

    @AgenticExposed(description = "Legacy lookup", channels = { AgenticExposed.Channel.API },
            apiDeprecatedSince = 1, apiReplacement = "list")
    public String lookup() { return null; }
}
