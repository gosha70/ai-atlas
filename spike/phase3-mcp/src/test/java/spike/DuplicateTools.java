package spike;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Q5: a generated @Tool bean whose name ALSO has a SyncToolSpecification — both paths register it. */
@Service
@ConditionalOnProperty(name = "spike.duplicate", havingValue = "true")
public class DuplicateTools {

    @Tool(name = "hinted_explicit", description = "Same name as the hinted SyncToolSpecification.")
    public String dup(int count) {
        return "dup " + count;
    }
}
