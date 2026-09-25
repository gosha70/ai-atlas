package test;
import com.egoge.ai.atlas.annotations.AgenticExposed;
@AgenticExposed(description = "Legacy", channels = { AgenticExposed.Channel.API })
public class LegacyService {
    public String OrderService_find_get() { return null; }
}
