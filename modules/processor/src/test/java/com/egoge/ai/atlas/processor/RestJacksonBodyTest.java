/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.RestTestSupport.GeneratedClasses;
import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.stream.Stream;

import static com.egoge.ai.atlas.processor.RestTestSupport.ORDER;
import static com.egoge.ai.atlas.processor.RestTestSupport.REST_ON;
import static com.egoge.ai.atlas.processor.RestTestSupport.call;
import static com.egoge.ai.atlas.processor.RestTestSupport.compile;
import static com.egoge.ai.atlas.processor.RestTestSupport.compileUnchecked;
import static com.egoge.ai.atlas.processor.RestTestSupport.errors;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Jackson annotations that make a request body deserialize an {@code @AgenticEntity} its Java types
 * do not show: the classes they name are walked like any other reached type, and those that let
 * the class be chosen out of the processor's sight are a compile error. Were one to compile, the
 * request shows the non-{@code @AgenticField} {@code ssn} it would let reach the service.
 */
class RestJacksonBodyTest {

    private static final String ORDER_JSON = "{\"id\":8,\"status\":\"PAID\",\"ssn\":\"SECRET\"}";
    private static final String JACKSON = """
            package shop;
            import com.fasterxml.jackson.annotation.*;
            import com.fasterxml.jackson.databind.annotation.*;
            import java.util.*;
            """;

    static Stream<Arguments> bypasses() {
        return Stream.of(
                Arguments.of("Loose", "(Order) body.value", "{\"value\":" + ORDER_JSON + "}", """
                        public class Loose { @JsonDeserialize(as = Order.class) public Object value; }
                        """, "reaches the @AgenticEntity Order through shop.Loose.value @JsonDeserialize(as)"),
                Arguments.of("ByGetter", "(Order) body.getValue()", "{\"value\":" + ORDER_JSON + "}", """
                        public class ByGetter {
                            private Object value;
                            @JsonDeserialize(as = Order.class) public Object getValue() { return value; }
                            public void setValue(Object value) { this.value = value; }
                        }
                        """, "reaches the @AgenticEntity Order through shop.ByGetter.getValue @JsonDeserialize(as)"),
                Arguments.of("Sub", "(Order) body.value", "{\"value\":{\"t\":\"o\"," + ORDER_JSON.substring(1) + "}", """
                        public class Sub {
                            @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "t")
                            @JsonSubTypes(@JsonSubTypes.Type(value = Order.class, name = "o")) public Object value;
                        }
                        """, "reaches the @AgenticEntity Order through shop.Sub.value @JsonSubTypes"),
                Arguments.of("Made", "(Order) body.value", "{\"order\":" + ORDER_JSON + "}", """
                        public class Made {
                            public final Object value;
                            private Made(Object value) { this.value = value; }
                            @JsonCreator public static Made of(@JsonProperty("order") Order order) { return new Made(order); }
                        }
                        """, "reaches the @AgenticEntity Order through shop.Made.of()"),
                Arguments.of("Named", "(Order) body.kept", "{\"order\":" + ORDER_JSON + "}", """
                        public class Named {
                            public Object kept;
                            @JsonSetter("order") public void put(Order order) { kept = order; }
                        }
                        """, "reaches the @AgenticEntity Order through shop.Named.put()"),
                Arguments.of("Any", "(Order) body.extra.get(\"o\")", "{\"o\":" + ORDER_JSON + "}", """
                        public class Any {
                            public Map<String, Object> extra = new HashMap<>();
                            @JsonAnySetter public void add(String key, Order value) { extra.put(key, value); }
                        }
                        """, "reaches the @AgenticEntity Order through shop.Any.add()"),
                Arguments.of("Custom", "(Order) body.value", "{\"value\":" + ORDER_JSON + "}", """
                        public class Custom {
                            @JsonDeserialize(using = Custom.Reader.class) public Object value;
                            public static class Reader extends com.fasterxml.jackson.databind.JsonDeserializer<Object> {
                                @Override public Object deserialize(com.fasterxml.jackson.core.JsonParser parser,
                                        com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
                                    return parser.readValueAs(Order.class);
                                }
                            }
                        }
                        """, "which Jackson deserializes through @JsonDeserialize(using = shop.Custom.Reader) on"
                        + " shop.Custom.value"),
                Arguments.of("Typed", "(Order) body.value", "{\"value\":{\"@class\":\"shop.Order\","
                        + ORDER_JSON.substring(1) + "}", """
                        public class Typed { @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS) public Object value; }
                        """, "which Jackson deserializes through @JsonTypeInfo(use = CLASS) on shop.Typed.value"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bypasses")
    void aJacksonAnnotationThatBindsAnEntityIsAnError(String type, String extract, String json, String wrapper,
                                                     String message) throws Exception {
        Compilation compilation = compileUnchecked(List.of(REST_ON), ORDER, JACKSON + wrapper, service(type, extract));

        if (compilation.status() == Compilation.Status.SUCCESS) {
            GeneratedClasses classes = new GeneratedClasses(compilation);
            call(classes.mvc("WrapService"), post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(json));
            Object placed = classes.load("shop.WrapService").getField("last").get(null);
            // A class-name type id resolves only classes Jackson's loader sees, which a compiled-in-memory Order is not
            if (placed != null) {
                assertThat(placed.getClass().getMethod("getSsn").invoke(placed))
                        .as("the non-whitelisted ssn bound through " + type).isNull();
            }
        }
        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(errors(compilation)).anyMatch(e -> e.contains("The request body 'body' of 'place' ")
                && e.contains(message));
    }

    @Test
    void jacksonAnnotationsThatReachNoEntityStillCompile() {
        String command = JACKSON + """
                public class Command {
                    @JsonDeserialize(as = ArrayList.class) public List<String> tags;
                    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "t")
                    @JsonSubTypes(@JsonSubTypes.Type(value = Note.class, name = "n")) public Object detail;
                    @JsonSetter("label") public void rename(String label) { }
                    public static class Note { public String text; }
                }
                """;
        compile(List.of(REST_ON), ORDER, command, service("Command", "null"));
    }

    private static String service(String type, String extract) {
        return """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.*;
                import com.egoge.ai.atlas.annotations.AgenticParam.In;
                @AgenticExposed(rest = @Rest(resource = "orders"))
                public class WrapService {
                    public static Order last;
                    @AgenticExposed(description = "Place", rest = @Rest(method = HttpMethod.POST, path = ""))
                    public void place(@AgenticParam(in = In.BODY) %s body) { last = %s; }
                }
                """.formatted(type, extract);
    }
}
