package spike;

import jakarta.validation.constraints.Max;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/** Q4/Q5: server-side enforcement via Spring method validation needs @Validated, i.e. a CGLIB proxy. */
@Service
@Validated
public class ValidatedTools {

    @Tool(name = "validated_max", description = "@Validated bean, @Max(100) on the parameter.")
    public String validatedMax(@Max(100) int count) {
        return "invoked count=" + count;
    }
}
