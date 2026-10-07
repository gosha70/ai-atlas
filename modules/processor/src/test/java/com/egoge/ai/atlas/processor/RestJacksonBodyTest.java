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
                Arguments.of("Converted", "(Order) body.value", "{\"value\":" + ORDER_JSON + "}", """
                        public class Converted {
                            @JsonDeserialize(converter = ToOrder.class) public Object value;
                            public static class ToOrder
                                    extends com.fasterxml.jackson.databind.util.StdConverter<Map<String, Object>, Order> {
                                @Override public Order convert(Map<String, Object> map) {
                                    Order order = new Order();
                                    order.setSsn((String) map.get("ssn"));
                                    return order;
                                }
                            }
                        }
                        """, "which Jackson deserializes through @JsonDeserialize(converter = shop.Converted.ToOrder) on"
                        + " shop.Converted.value"),
                Arguments.of("ContentConverted", "(Order) body.values.get(0)", "{\"values\":[\"SECRET\"]}", """
                        public class ContentConverted {
                            @JsonDeserialize(contentConverter = ToOrder.class) public List<Object> values;
                            public static class ToOrder extends com.fasterxml.jackson.databind.util.StdConverter<String, Order> {
                                @Override public Order convert(String ssn) {
                                    Order order = new Order();
                                    order.setSsn(ssn);
                                    return order;
                                }
                            }
                        }
                        """, "which Jackson deserializes through @JsonDeserialize(contentConverter ="),
                Arguments.of("Bundled", "(Order) body.value", "{\"value\":" + ORDER_JSON + "}", """
                        public class Bundled {
                            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                            @JacksonAnnotationsInside @JsonDeserialize(as = Order.class) public @interface AsOrder { }
                            @AsOrder public Object value;
                        }
                        """, "reaches the @AgenticEntity Order through shop.Bundled.value @JsonDeserialize(as)"),
                Arguments.of("Fallback", "(Order) body.value", "{\"value\":" + ORDER_JSON + "}", """
                        public class Fallback {
                            @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "t", defaultImpl = Order.class)
                            public Object value;
                        }
                        """, "reaches the @AgenticEntity Order through shop.Fallback.value @JsonTypeInfo(defaultImpl)"),
                Arguments.of("Built", "(Order) body.value", "{\"ssn\":\"SECRET\"}", """
                        @JsonDeserialize(builder = Built.Maker.class)
                        public class Built {
                            public Object value;
                            public static class Maker {
                                private String ssn;
                                public Maker withSsn(String ssn) { this.ssn = ssn; return this; }
                                public Built build() {
                                    Order order = new Order();
                                    order.setSsn(ssn);
                                    Built built = new Built();
                                    built.value = order;
                                    return built;
                                }
                            }
                        }
                        """, "which Jackson deserializes through @JsonDeserialize(builder = shop.Built.Maker) on shop.Built"),
                Arguments.of("Held", "body.getValues().get(0)", "{\"values\":[" + ORDER_JSON + "]}", """
                        public class Held {
                            @SuppressWarnings("rawtypes") private final List raw = new ArrayList();
                            @SuppressWarnings("unchecked") @JsonProperty public List<Order> getValues() { return raw; }
                        }
                        """, "reaches the @AgenticEntity Order through shop.Held.getValues()"),
                Arguments.of("NamedNone", "(Order) body.value", "{\"value\":" + ORDER_JSON + "}", """
                        public class NamedNone {
                            @JsonDeserialize(using = NamedNone.None.class) public Object value;
                            public static class None extends com.fasterxml.jackson.databind.JsonDeserializer<Object> {
                                @Override public Object deserialize(com.fasterxml.jackson.core.JsonParser parser,
                                        com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
                                    return parser.readValueAs(Order.class);
                                }
                            }
                        }
                        """, "which Jackson deserializes through @JsonDeserialize(using = shop.NamedNone.None) on"
                        + " shop.NamedNone.value"),
                Arguments.of("Typed", "(Order) body.value", "{\"value\":{\"@class\":\"shop.Order\","
                        + ORDER_JSON.substring(1) + "}", """
                        public class Typed { @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS) public Object value; }
                        """, "which Jackson deserializes through @JsonTypeInfo(use = CLASS) on shop.Typed.value"),
                // Every value of a multi-parameter factory is bound, not only the last as for a setter
                Arguments.of("Pair", "(Order) body.kept", "{\"order\":" + ORDER_JSON + ",\"note\":\"n\"}", """
                        public class Pair {
                            public Object kept;
                            @JsonCreator public static Pair of(@JsonProperty("order") Order order,
                                    @JsonProperty("note") String note) { Pair pair = new Pair(); pair.kept = order; return pair; }
                        }
                        """, "reaches the @AgenticEntity Order through shop.Pair.of()"),
                // A getter Jackson fills that only @JsonProperty, not its name, makes a property
                Arguments.of("Listed", "body.values().get(0)", "{\"values\":[" + ORDER_JSON + "]}", """
                        public class Listed {
                            private final List<Object> raw = new ArrayList<>();
                            @SuppressWarnings({"unchecked", "rawtypes"})
                            @JsonProperty("values") public List<Order> values() { return (List) raw; }
                        }
                        """, "reaches the @AgenticEntity Order through shop.Listed.values()"),
                Arguments.of("GotList", "body.orders().get(0)", "{\"orders\":[" + ORDER_JSON + "]}", """
                        public class GotList {
                            private final List<Object> raw = new ArrayList<>();
                            @SuppressWarnings({"unchecked", "rawtypes"})
                            @JsonGetter("orders") public List<Order> orders() { return (List) raw; }
                        }
                        """, "reaches the @AgenticEntity Order through shop.GotList.orders()"),
                Arguments.of("GotMap", "body.m().get(\"o\")", "{\"m\":{\"o\":" + ORDER_JSON + "}}", """
                        public class GotMap {
                            private final Map<String, Object> raw = new HashMap<>();
                            @SuppressWarnings({"unchecked", "rawtypes"})
                            @JsonGetter("m") public Map<String, Order> m() { return (Map) raw; }
                        }
                        """, "reaches the @AgenticEntity Order through shop.GotMap.m()"),
                // Jackson infers a property from other annotations too: @JsonView makes put a setter
                Arguments.of("Viewed", "(Order) body.kept", "{\"put\":" + ORDER_JSON + "}", """
                        public class Viewed {
                            public Object kept;
                            @JsonView(Object.class) public void put(Order order) { kept = order; }
                        }
                        """, "reaches the @AgenticEntity Order through shop.Viewed.put()"),
                Arguments.of("Formatted", "body.orders().get(0)", "{\"orders\":[" + ORDER_JSON + "]}", """
                        public class Formatted {
                            private final List<Object> raw = new ArrayList<>();
                            @SuppressWarnings({"unchecked", "rawtypes"})
                            @JsonFormat public List<Order> orders() { return (List) raw; }
                        }
                        """, "reaches the @AgenticEntity Order through shop.Formatted.orders()"),
                // Two method type variables of one name are two variables, in either order
                Arguments.of("GenericSetters", "(Order) body.kept", "{\"b\":" + ORDER_JSON + "}", """
                        public class GenericSetters {
                            public Object kept;
                            public <T> void setA(T a) { }
                            public <T extends Order> void setB(T b) { kept = b; }
                        }
                        """, "reaches the @AgenticEntity Order through shop.GenericSetters.setB()"),
                Arguments.of("GenericSettersSwapped", "(Order) body.kept", "{\"b\":" + ORDER_JSON + "}", """
                        public class GenericSettersSwapped {
                            public Object kept;
                            public <T extends Order> void setB(T b) { kept = b; }
                            public <T> void setA(T a) { }
                        }
                        """, "reaches the @AgenticEntity Order through shop.GenericSettersSwapped.setB()"),
                Arguments.of("GenericLists", "(Order) body.kept.get(0)", "{\"b\":[" + ORDER_JSON + "]}", """
                        public class GenericLists {
                            public List<?> kept;
                            public <T> void setA(List<T> a) { }
                            public <T extends Order> void setB(List<T> b) { kept = b; }
                        }
                        """, "reaches the @AgenticEntity Order through shop.GenericLists.setB()"),
                Arguments.of("GenericFactory", "(Order) body.kept", "{\"b\":" + ORDER_JSON + "}", """
                        public class GenericFactory {
                            public Object kept;
                            public <T> void setA(T a) { }
                            @JsonCreator public static <T extends Order> GenericFactory of(@JsonProperty("b") T b) {
                                GenericFactory made = new GenericFactory();
                                made.kept = b;
                                return made;
                            }
                        }
                        """, "reaches the @AgenticEntity Order through shop.GenericFactory.of()"),
                Arguments.of("GenericConstructor", "(Order) body.kept", "{\"b\":" + ORDER_JSON + "}", """
                        public class GenericConstructor {
                            public Object kept;
                            public <T> void setA(T a) { }
                            @JsonCreator public <T extends Order> GenericConstructor(@JsonProperty("b") T b) { kept = b; }
                        }
                        """, "reaches the @AgenticEntity Order through shop.GenericConstructor(b)"));
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

    static Stream<Arguments> uncheckable() {
        return Stream.of(
                Arguments.of("Injected", """
                        public class Injected { @JacksonInject("order") public Object value; }
                        """, "which Jackson deserializes through @JacksonInject on shop.Injected.value"),
                Arguments.of("Merged", """
                        public class Merged { @JsonMerge public Object value = new Order(); }
                        """, "which Jackson deserializes through @JsonMerge on shop.Merged.value"),
                Arguments.of("Resolved", """
                        @JsonIdentityInfo(generator = ObjectIdGenerators.IntSequenceGenerator.class, resolver = Resolved.R.class)
                        public class Resolved {
                            public Object value;
                            public static class R extends SimpleObjectIdResolver { }
                        }
                        """, "which Jackson deserializes through @JsonIdentityInfo(resolver = shop.Resolved.R) on shop.Resolved"),
                Arguments.of("Valued", """
                        public class Valued {
                            public Object value;
                            public static Valued valueOf(Order order) { Valued valued = new Valued(); valued.value = order; return valued; }
                        }
                        """, "reaches the @AgenticEntity Order through shop.Valued.valueOf()"),
                Arguments.of("InjectedFactory", """
                        public class InjectedFactory {
                            public Object value;
                            @JsonCreator public static InjectedFactory of(@JacksonInject("order") Object order) {
                                InjectedFactory made = new InjectedFactory();
                                made.value = order;
                                return made;
                            }
                        }
                        """, "which Jackson deserializes through @JacksonInject on shop.InjectedFactory.of()"),
                Arguments.of("InjectedConstructor", """
                        public class InjectedConstructor {
                            public final Object value;
                            @JsonCreator public InjectedConstructor(@JacksonInject("order") Object value) { this.value = value; }
                        }
                        """, "which Jackson deserializes through @JacksonInject on shop.InjectedConstructor(value)"));
    }

    /** Annotations whose value code chooses, and an implicit factory, each without a request to show it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("uncheckable")
    void anAnnotationWhoseValueCodeChoosesIsAnError(String type, String wrapper, String message) {
        assertThat(errors(compileUnchecked(List.of(REST_ON), ORDER, JACKSON + wrapper, service(type, "null"))))
                .anyMatch(e -> e.contains("The request body 'body' of 'place' ") && e.contains(message));
    }

    @Test
    void aCustomTypeResolverOrValueInstantiatorIsAnError() {
        String resolved = JACKSON + """
                @JsonTypeResolver(com.fasterxml.jackson.databind.jsontype.impl.StdTypeResolverBuilder.class)
                public class Resolved { public Object value; }
                """;
        String instantiated = JACKSON + """
                @JsonValueInstantiator(com.fasterxml.jackson.databind.deser.std.StdValueInstantiator.class)
                public class Instantiated { public Object value; }
                """;

        assertThat(errors(compileUnchecked(List.of(REST_ON), ORDER, resolved, service("Resolved", "null"))))
                .anyMatch(e -> e.contains("which Jackson deserializes through @JsonTypeResolver on shop.Resolved"));
        assertThat(errors(compileUnchecked(List.of(REST_ON), ORDER, instantiated, service("Instantiated", "null"))))
                .anyMatch(e -> e.contains("which Jackson deserializes through @JsonValueInstantiator on shop.Instantiated"));
    }

    @Test
    void jacksonAnnotationsThatReachNoEntityStillCompile() {
        String command = JACKSON + """
                public class Command {
                    @JsonDeserialize(as = ArrayList.class) public List<String> tags;
                    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "t")
                    @JsonSubTypes(@JsonSubTypes.Type(value = Note.class, name = "n")) public Object detail;
                    @JsonSetter("label") public void rename(String label) { }
                    @JsonIgnore public Object scratch;
                    @JsonAlias("when") @JsonFormat(pattern = "yyyy-MM-dd") public String date;
                    @JsonIgnoreProperties(ignoreUnknown = true) public Note note;
                    @JsonDeserialize(using = com.fasterxml.jackson.databind.JsonDeserializer.None.class,
                            converter = com.fasterxml.jackson.databind.util.Converter.None.class) public String plain;
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
