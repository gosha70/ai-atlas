package spike;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;

/** Q1/Q4: the shape of today's generated tool bean — schema derived by Spring AI from the method. */
@Service
public class DerivedTools {

    @Tool(name = "derived_bean_validation", description = "Bean Validation annotations on parameters.")
    public String beanValidation(@Min(1) @Max(100) int count,
                                 @Size(min = 2, max = 10) @Pattern(regexp = "^[a-z]+$") String code,
                                 @Size(min = 1, max = 3) List<String> tags) {
        return "invoked count=" + count + " code=" + code + " tags=" + tags;
    }

    @Tool(name = "derived_tool_param", description = "@ToolParam on the parameter.")
    public String toolParam(@ToolParam(description = "Page size, 1..100", required = true) int count) {
        return "invoked count=" + count;
    }

    /** Constraints on a record component instead of a method parameter. */
    public record Page(@Min(1) @Max(100) @Schema(minimum = "1", maximum = "100") int count,
                       @Size(min = 2, max = 10) @Schema(minLength = 2, maxLength = 10, pattern = "^[a-z]+$")
                       String code) {
    }

    /** Jakarta constraints only, no @Schema: shows which annotation family the generator reads. */
    public record JakartaOnly(@Min(1) @Max(100) int count, @Size(min = 2, max = 10) String code) {
    }

    @Tool(name = "derived_record_jakarta_only", description = "Jakarta constraints only on record components.")
    public String recordJakartaOnly(JakartaOnly page) {
        return "invoked " + page;
    }

    @Tool(name = "derived_record_arg", description = "Constraints on record components (jakarta + @Schema).")
    public String recordArg(Page page) {
        return "invoked " + page;
    }
}
