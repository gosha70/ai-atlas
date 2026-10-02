/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.RestTestSupport.GeneratedClasses;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.egoge.ai.atlas.processor.RestTestSupport.CONSTRAINTS_ON;
import static com.egoge.ai.atlas.processor.RestTestSupport.CUSTOMER;
import static com.egoge.ai.atlas.processor.RestTestSupport.CUSTOMER_SERVICE;
import static com.egoge.ai.atlas.processor.RestTestSupport.ORDER;
import static com.egoge.ai.atlas.processor.RestTestSupport.ORDER_SERVICE;
import static com.egoge.ai.atlas.processor.RestTestSupport.PROJECTIONS_ON;
import static com.egoge.ai.atlas.processor.RestTestSupport.REST_ON;
import static com.egoge.ai.atlas.processor.RestTestSupport.call;
import static com.egoge.ai.atlas.processor.RestTestSupport.compile;
import static com.egoge.ai.atlas.processor.RestTestSupport.compileUnchecked;
import static com.egoge.ai.atlas.processor.RestTestSupport.errors;
import static com.egoge.ai.atlas.processor.RestTestSupport.messages;
import static com.egoge.ai.atlas.processor.RestTestSupport.openApi;
import static com.egoge.ai.atlas.processor.RestTestSupport.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * {@code ai.atlas.rest}: an {@code @AgenticEntity} request body binds a generated, whitelisted
 * {@code <Entity>Input} record, never the entity, so a property without {@code @AgenticField} is
 * not bound; the OpenAPI document describes exactly that record; Phase 4's input rule holds; and
 * an entity the record cannot create is a compile error.
 */
class RestInputRecordTest {

    private static final String PLACE = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import com.egoge.ai.atlas.annotations.AgenticExposed.*;
            @AgenticExposed(rest = @Rest(resource = "items"))
            public class ItemService {
                public static Item last;
                @AgenticExposed(description = "Place", rest = @Rest(method = HttpMethod.POST, path = "", status = 201))
                public void place(Item item) { last = item; }
            }
            """;

    @Test
    void aPropertyWithoutAgenticFieldIsNotBoundFromTheBody() throws Exception {
        Compilation compilation = compile(List.of(REST_ON), ORDER, ORDER_SERVICE);
        GeneratedClasses classes = new GeneratedClasses(compilation);
        MockMvc mvc = classes.mvc("OrderService");

        assertThat(call(mvc, post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":8,\"status\":\"PAID\",\"ssn\":\"123-45-6789\"}")))
                .isEqualTo("201 {\"id\":8,\"status\":\"PAID\"}");

        Object placed = classes.load("shop.OrderService").getField("lastPlaced").get(null);
        assertThat(placed.getClass().getMethod("getStatus").invoke(placed)).isEqualTo("PAID");
        assertThat(placed.getClass().getMethod("getSsn").invoke(placed)).isNull();
        assertThat(source(compilation, "shop.generated.OrderServiceRestController"))
                .contains("public OrderDto place(@RequestBody OrderInput order)")
                .contains("return OrderDto.fromEntity(service.place(order.toEntity()));");
        assertThat(source(compilation, "shop.generated.OrderInput"))
                .contains("public record OrderInput(Long id, String status)")
                .doesNotContain("ssn");
    }

    @Test
    void aFieldDeclaredInputFalseIsNotBound() throws Exception {
        Compilation compilation = compile(List.of(REST_ON), CUSTOMER, CUSTOMER_SERVICE);
        MockMvc mvc = new GeneratedClasses(compilation).mvc("CustomerService");

        // The service assigns ids; a client-sent id is ignored on create and update
        assertThat(call(mvc, post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":99,\"name\":\"Ada\"}"))).isEqualTo("201 {\"id\":1,\"name\":\"Ada\"}");
        assertThat(source(compilation, "shop.generated.CustomerInput"))
                .contains("public record CustomerInput(String name)")
                .contains("entity.setName(name);");
        JsonNode schema = openApi(compilation).path("components").path("schemas").path("CustomerInput");
        assertThat(schema.path("properties").fieldNames()).toIterable().containsExactly("name");
    }

    @Test
    void theOpenApiRequestBodyReferencesTheInputRecordWithItsRequiredFields() throws Exception {
        String item = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import jakarta.validation.constraints.NotBlank;
                @AgenticEntity(description = "An item")
                public class Item {
                    @AgenticField(description = "Name") @NotBlank private String name;
                    @AgenticField(description = "Quantity") private int quantity;
                    @AgenticField(description = "Note") private String note;
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public int getQuantity() { return quantity; }
                    public void setQuantity(int quantity) { this.quantity = quantity; }
                    public String getNote() { return note; }
                    public void setNote(String note) { this.note = note; }
                }
                """;
        JsonNode doc = openApi(compile(List.of(REST_ON), item, PLACE));

        JsonNode body = doc.path("paths").path("/api/v1/items").path("post").path("requestBody");
        assertThat(body.path("required").asBoolean()).isTrue();
        assertThat(body.path("content").path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/ItemInput");
        JsonNode schema = doc.path("components").path("schemas").path("ItemInput");
        assertThat(schema.path("properties").fieldNames()).toIterable().containsExactly("name", "quantity", "note");
        assertThat(schema.path("required").toString()).isEqualTo("[\"name\",\"quantity\"]");
        // The entity's DTO schema is unchanged: it describes responses
        assertThat(doc.path("components").path("schemas").path("ItemDto").has("required")).isFalse();
    }

    @Test
    void anEntityWithAMatchingConstructorIsCreatedThroughIt() throws Exception {
        String item = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity(description = "An immutable item")
                public class Item {
                    @AgenticField(description = "Name") private final String name;
                    @AgenticField(description = "Quantity") private final int quantity;
                    private final String secret;
                    public Item(String name, int quantity) { this(name, quantity, null); }
                    public Item(String name, int quantity, String secret) {
                        this.name = name; this.quantity = quantity; this.secret = secret;
                    }
                    public String getName() { return name; }
                    public int getQuantity() { return quantity; }
                    public String getSecret() { return secret; }
                }
                """;
        Compilation compilation = compile(List.of(REST_ON), item, PLACE);
        GeneratedClasses classes = new GeneratedClasses(compilation);

        assertThat(call(classes.mvc("ItemService"), post("/api/v1/items").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"pen\",\"quantity\":3,\"secret\":\"x\"}"))).isEqualTo("201 ");
        Object placed = classes.load("shop.ItemService").getField("last").get(null);
        assertThat(placed.getClass().getMethod("getName").invoke(placed)).isEqualTo("pen");
        assertThat(placed.getClass().getMethod("getQuantity").invoke(placed)).isEqualTo(3);
        assertThat(placed.getClass().getMethod("getSecret").invoke(placed)).isNull();
        assertThat(source(compilation, "shop.generated.ItemInput")).contains("return new Item(name, quantity);");
    }

    @Test
    void anEntityTheInputRecordCannotCreateIsAnError() {
        String item = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity(description = "An item")
                public class Item {
                    @AgenticField(description = "Name") private String name;
                    @AgenticField(description = "Quantity") private int quantity;
                    public Item(String name) { this.name = name; }
                    public String getName() { return name; }
                    public int getQuantity() { return quantity; }
                    public void setQuantity(int quantity) { this.quantity = quantity; }
                }
                """;
        Compilation compilation = compileUnchecked(List.of(REST_ON), item, PLACE);

        assertThat(errors(compilation))
                .anyMatch(e -> e.contains("The @AgenticEntity Item is a request body, but its input record ItemInput"
                        + " cannot create it. It needs an accessible no-argument constructor (none found) and a setter"
                        + " for each input field (missing: setName), or an accessible constructor taking"
                        + " (java.lang.String name, int quantity) in that order"))
                .anyMatch(e -> e.contains("The request body 'item' of 'place' cannot bind the @AgenticEntity Item"));
    }

    @Test
    void aRequiredFieldNotEligibleForTheApiChannelIsAnErrorAndAnOptionalOneIsLeftOut() {
        String item = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
                import jakarta.validation.constraints.NotNull;
                @AgenticEntity(description = "An item")
                public class Item {
                    @AgenticField(description = "Name") private String name;
                    @AgenticField(description = "Hint", channels = Channel.AI) private String hint;
                    @AgenticField(description = "Score", channels = Channel.AI) %s private Integer score;
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public String getHint() { return hint; }
                    public void setHint(String hint) { this.hint = hint; }
                    public Integer getScore() { return score; }
                    public void setScore(Integer score) { this.score = score; }
                }
                """;
        Compilation optional = compile(List.of(REST_ON, PROJECTIONS_ON), item.formatted(""), PLACE);
        assertThat(source(optional, "shop.generated.ItemInput")).contains("public record ItemInput(String name)");

        Compilation required = compileUnchecked(List.of(REST_ON, PROJECTIONS_ON), item.formatted("@NotNull"), PLACE);
        assertThat(errors(required)).anyMatch(e -> e.contains("Field 'score' of Item is required (a primitive, or"
                + " @NotNull, @NotBlank or @NotEmpty), but not eligible for the API channel, so a REST request body"
                + " cannot set it"));
    }

    @Test
    void aFieldReferringToAnEntityCannotBeAnInput() {
        String line = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity(description = "A line")
                public class Line {
                    @AgenticField(description = "Order") private Order order;
                    @AgenticField(description = "Quantity") private int quantity;
                    public Order getOrder() { return order; }
                    public void setOrder(Order order) { this.order = order; }
                    public int getQuantity() { return quantity; }
                    public void setQuantity(int quantity) { this.quantity = quantity; }
                }
                """;
        String service = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.*;
                @AgenticExposed(rest = @Rest(style = RestStyle.CRUD, resource = "lines"))
                public class LineService {
                    public void create(Line line) { }
                }
                """;
        Compilation compilation = compileUnchecked(List.of(REST_ON), ORDER, line, service);

        assertThat(errors(compilation)).anyMatch(e -> e.contains("Field 'order' of Line refers to the @AgenticEntity"
                + " Order, which a REST request body cannot bind. Declare @AgenticField(input = false) on it"));
        assertThat(compile(List.of(REST_ON), ORDER, line.replace("@AgenticField(description = \"Order\")",
                "@AgenticField(description = \"Order\", input = false)"), service).status())
                .isEqualTo(Compilation.Status.SUCCESS);
    }

    @Test
    void anEntitySubtypeBodyIsAnError() {
        String special = """
                package shop;
                public class SpecialOrder extends Order {
                }
                """;
        Compilation withSubtype = compileUnchecked(List.of(REST_ON), ORDER, special, """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.*;
                public class BadService {
                    @AgenticExposed(rest = @Rest(method = HttpMethod.POST, path = "")) public void a(SpecialOrder order) { }
                }
                """);

        assertThat(withSubtype.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(errors(withSubtype)).anyMatch(e -> e.contains("The request body 'order' of 'a' is shop.SpecialOrder,"
                + " a subtype of the @AgenticEntity Order, which a request body cannot bind through a whitelist"));
    }

    static Stream<Arguments> wrappedEntityBodies() {
        String order = "{\"id\":8,\"status\":\"PAID\",\"ssn\":\"SECRET\"}";
        return Stream.of(
                Arguments.of("java.util.Optional<shop.Order>", "body.orElse(null)", order, null,
                        "is java.util.Optional<shop.Order>, which Jackson binds as the @AgenticEntity Order itself"),
                Arguments.of("java.util.Map<String, Order>", "body.get(\"a\")", "{\"a\":" + order + "}", null,
                        "reaches the @AgenticEntity Order through java.util.Map<shop.Order>"),
                Arguments.of("java.util.Map<String, List<Order>>", "body.get(\"a\").get(0)", "{\"a\":[" + order + "]}",
                        null, "reaches the @AgenticEntity Order through"),
                Arguments.of("PlaceRequest", "body.order()", "{\"note\":\"n\",\"order\":" + order + "}", """
                        package shop;
                        public record PlaceRequest(Order order, String note) { }
                        """, "is shop.PlaceRequest, which reaches the @AgenticEntity Order through shop.PlaceRequest.order"),
                Arguments.of("Envelope", "body.getOrder()", "{\"order\":" + order + "}", """
                        package shop;
                        public class Envelope {
                            private Order order;
                            public Order getOrder() { return order; }
                            public void setOrder(Order order) { this.order = order; }
                        }
                        """, "is shop.Envelope, which reaches the @AgenticEntity Order through shop.Envelope.order"),
                Arguments.of("Wrapper<Wrapper<Order>>", "body.value().value()", "{\"value\":{\"value\":" + order + "}}", """
                        package shop;
                        public record Wrapper<T>(T value) { }
                        """, "reaches the @AgenticEntity Order through"),
                Arguments.of("SubEnvelope", "body.getOrder()", "{\"order\":" + order + "}", """
                        package shop;
                        public class SubEnvelope extends Base { }
                        class Base {
                            @com.fasterxml.jackson.annotation.JsonProperty private Order order;
                            public Order getOrder() { return order; }
                        }
                        """, "is shop.SubEnvelope, which reaches the @AgenticEntity Order through shop.SubEnvelope.order"),
                Arguments.of("Holder", "body.orders()[0]", "{\"orders\":[" + order + "]}", """
                        package shop;
                        public record Holder(SpecialOrder[] orders) { }
                        """, "is shop.Holder, which reaches the @AgenticEntity Order through shop.Holder.orders[]"));
    }

    /**
     * An entity wrapped in an {@code Optional}, a map, a record, a bean or a generic type is bound by
     * Jackson in full, so its non-{@code @AgenticField} {@code ssn} would reach the service: each is a
     * compile error. Were one to compile, the request below shows the {@code ssn} it would let through.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("wrappedEntityBodies")
    void aBodyThatReachesAnEntityThroughAWrapperIsAnError(String type, String extract, String json, String wrapper,
                                                           String message) throws Exception {
        String special = """
                package shop;
                public class SpecialOrder extends Order { }
                """;
        String service = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.*;
                import com.egoge.ai.atlas.annotations.AgenticParam.In;
                import java.util.*;
                @AgenticExposed(rest = @Rest(resource = "orders"))
                public class WrapService {
                    public static Order last;
                    @AgenticExposed(description = "Place", rest = @Rest(method = HttpMethod.POST, path = ""))
                    public void place(@AgenticParam(in = In.BODY) %s body) { last = %s; }
                }
                """.formatted(type, extract);
        List<String> sources = new ArrayList<>(List.of(ORDER, special, service));
        if (wrapper != null) {
            sources.add(wrapper);
        }
        Compilation compilation = compileUnchecked(List.of(REST_ON), sources.toArray(String[]::new));

        if (compilation.status() == Compilation.Status.SUCCESS) {
            GeneratedClasses classes = new GeneratedClasses(compilation);
            call(classes.mvc("WrapService"), post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(json));
            Object placed = classes.load("shop.WrapService").getField("last").get(null);
            assertThat(placed.getClass().getMethod("getSsn").invoke(placed))
                    .as("the non-whitelisted ssn bound through " + type).isNull();
        }
        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(errors(compilation)).anyMatch(e -> e.contains("The request body 'body' of 'place' ")
                && e.contains(message));
    }

    @Test
    void aBodyTypeThatReachesNoEntityIsBoundAsDeclaredEvenWhenItIsCyclic() throws Exception {
        String node = """
                package shop;
                import java.util.*;
                public record Node(String name, List<Node> children, Map<String, Integer> counts) { }
                """;
        String service = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.*;
                import com.egoge.ai.atlas.annotations.AgenticParam.In;
                @AgenticExposed(rest = @Rest(resource = "nodes"))
                public class NodeService {
                    @AgenticExposed(description = "Count", rest = @Rest(method = HttpMethod.POST, path = ""))
                    public int count(@AgenticParam(in = In.BODY) Node node) { return node.children().size(); }
                }
                """;
        Compilation compilation = compile(List.of(REST_ON), node, service);

        assertThat(call(new GeneratedClasses(compilation).mvc("NodeService"), post("/api/v1/nodes")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\",\"children\":[{\"name\":\"b\"}]}")))
                .isEqualTo("200 1");
    }

    private static final String REQUIRED_ITEM = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import jakarta.validation.constraints.NotNull;
            @AgenticEntity(description = "An item")
            public class Item {
                @AgenticField(description = "Name") @NotNull private String name;
                @AgenticField(description = "Quantity") private int qty;
                @AgenticField(description = "Note") private String note;
                private String ssn;
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
                public int getQty() { return qty; }
                public void setQty(int qty) { this.qty = qty; }
                public String getNote() { return note; }
                public void setNote(String note) { this.note = note; }
                public String getSsn() { return ssn; }
                public void setSsn(String ssn) { this.ssn = ssn; }
            }
            """;

    @Test
    void aMissingRequiredInputFieldIsRejected() throws Exception {
        Compilation compilation = compile(List.of(REST_ON, CONSTRAINTS_ON), REQUIRED_ITEM, PLACE);
        GeneratedClasses classes = new GeneratedClasses(compilation);
        MockMvc mvc = classes.mvc("ItemService");

        JsonNode schema = openApi(compilation).path("components").path("schemas").path("ItemInput");
        assertThat(schema.path("required").toString()).isEqualTo("[\"name\",\"qty\"]");
        for (String missing : List.of("{\"ssn\":\"SECRET\"}", "{\"qty\":1,\"ssn\":\"SECRET\"}",
                "{\"name\":null,\"qty\":1}", "{\"name\":\"pen\",\"ssn\":\"SECRET\"}", "{\"name\":\"pen\",\"qty\":null}")) {
            assertThat(call(mvc, post("/api/v1/items").contentType(MediaType.APPLICATION_JSON).content(missing)))
                    .as(missing).startsWith("400");
        }
        assertThat(classes.load("shop.ItemService").getField("last").get(null)).isNull();

        assertThat(call(mvc, post("/api/v1/items").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"pen\",\"qty\":0,\"ssn\":\"SECRET\"}"))).isEqualTo("201 ");
        Object placed = classes.load("shop.ItemService").getField("last").get(null);
        assertThat(placed.getClass().getMethod("getName").invoke(placed)).isEqualTo("pen");
        assertThat(placed.getClass().getMethod("getNote").invoke(placed)).isNull();
        assertThat(placed.getClass().getMethod("getSsn").invoke(placed)).isNull();
        assertThat(source(compilation, "shop.generated.ItemInput"))
                .contains("public record ItemInput(String name, Integer qty, String note)")
                .contains("throw new IllegalArgumentException(\"'name' is required\");")
                .contains("throw new IllegalArgumentException(\"'qty' is required\");")
                .doesNotContain("'note' is required");
    }

    @Test
    void withConstraintsOffTheInputRecordStillEnforcesItsRequiredComponents() throws Exception {
        Compilation compilation = compile(List.of(REST_ON), REQUIRED_ITEM, PLACE);
        GeneratedClasses classes = new GeneratedClasses(compilation);

        JsonNode schema = openApi(compilation).path("components").path("schemas").path("ItemInput");
        assertThat(schema.path("required").toString()).isEqualTo("[\"name\",\"qty\"]");
        assertThat(source(compilation, "shop.generated.ItemInput"))
                .contains("public record ItemInput(String name, Integer qty, String note)")
                .contains("throw new IllegalArgumentException(\"'name' is required\");");
        MockMvc mvc = classes.mvc("ItemService");
        assertThat(call(mvc, post("/api/v1/items").contentType(MediaType.APPLICATION_JSON).content("{}")))
                .startsWith("400");
        assertThat(classes.load("shop.ItemService").getField("last").get(null)).isNull();
        assertThat(call(mvc, post("/api/v1/items").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"pen\",\"qty\":0}"))).isEqualTo("201 ");
    }

    @Test
    void anInputRecordNameTakenByAnotherTypeIsAnError() {
        String taken = """
                package shop.generated;
                public class OrderInput {
                }
                """;
        Compilation compilation = compileUnchecked(List.of(REST_ON), ORDER, ORDER_SERVICE, taken);

        assertThat(errors(compilation)).anyMatch(e -> e.contains("The input record shop.generated.OrderInput of Order"
                + " collides with a type the compilation declares"));
    }

    @Test
    void inputFalseWhileTheOptionIsOffIsAWarning() {
        Compilation compilation = compile(List.of(), CUSTOMER);

        assertThat(messages(compilation, Diagnostic.Kind.WARNING)).anyMatch(w -> w.contains(
                "@AgenticField(input = false) on field 'id' of Customer has no effect without ai.atlas.rest=true"));
    }

    @Test
    void anOptionalBodyIsPassedAsNullWhenAbsent() throws Exception {
        String service = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.*;
                @AgenticExposed(rest = @Rest(resource = "customers"))
                public class CustomerService {
                    @AgenticExposed(description = "Upsert", rest = @Rest(method = HttpMethod.PUT, path = "/{id}"))
                    public String upsert(Long id, @AgenticParam(required = Requiredness.OPTIONAL) Customer customer) {
                        return customer == null ? "none" : customer.getName();
                    }
                }
                """;
        Compilation compilation = compile(List.of(REST_ON, RestTestSupport.CONSTRAINTS_ON), CUSTOMER, service);
        MockMvc mvc = new GeneratedClasses(compilation).mvc("CustomerService");

        assertThat(source(compilation, "shop.generated.CustomerServiceRestController"))
                .contains("@RequestBody(required = false) CustomerInput customer")
                .contains("service.upsert(id, customer != null ? customer.toEntity() : null)");
        assertThat(call(mvc, put("/api/v1/customers/1"))).isEqualTo("200 none");
        assertThat(call(mvc, put("/api/v1/customers/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ada\"}"))).isEqualTo("200 Ada");
        assertThat(openApi(compilation).path("paths").path("/api/v1/customers/{id}").path("put").path("requestBody")
                .path("required").asBoolean()).isFalse();
    }
}
