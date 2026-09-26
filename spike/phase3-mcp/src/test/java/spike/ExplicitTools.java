package spike;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Target methods for the explicitly-specified tools. Deliberately NOT a {@code @Service} and has no
 * {@code @Tool}, so the Atlas LazyToolCallbackProvider does not also register it (which would
 * collide on the tool name — see DuplicateToolNameTest).
 */
@Component
public class ExplicitTools {

    public String pageSize(int count, String code, List<String> tags) {
        return "invoked count=" + count + " code=" + code + " tags=" + tags;
    }
}
