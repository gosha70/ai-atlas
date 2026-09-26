package shop.late;
import com.egoge.ai.atlas.annotations.AgenticEntity;
import com.egoge.ai.atlas.annotations.AgenticField;
@AgenticEntity
public class Late {
    @AgenticField private String code;
    public String getCode() { return code; }
}
