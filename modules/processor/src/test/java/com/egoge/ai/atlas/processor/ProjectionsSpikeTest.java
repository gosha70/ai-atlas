/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.contract.ContractGate;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.IrJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>Spike (Phase 4, epic #23 §7).</strong> Proves the per-channel projection prototype behind
 * {@code ai.atlas.projections}: field eligibility x operation channel decides each response's
 * shape; the REST controller and OpenAPI get the API projection under the existing DTO name, the
 * MCP tool gets the AI projection; nested entities and collections are projected recursively; and
 * with the option off, or with it on and no eligibility declared, the output is byte-identical.
 *
 * <p>The generated classes are loaded and called: REST responses are serialized with Jackson as
 * Spring MVC would, MCP results through Spring AI's own {@link MethodToolCallbackProvider}.
 */
class ProjectionsSpikeTest {

    private static final String FLAG_ON = "-Aai.atlas.projections=true";
    private static final String PARAMETERS = "-parameters";
    private static final ObjectMapper JSON = new ObjectMapper();

    // Modelled on the demo's Order / OrderAction / Customer, with eligibility declared
    private static final String ORDER_SRC = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
            import java.util.List;
            @AgenticEntity(description = "A customer order")
            public class Order {
                @AgenticField(description = "Order id") private Long id;
                @AgenticField(description = "Status") private String status;
                @AgenticField(description = "Internal margin, for REST back-office clients"%s) private Integer marginCents;
                @AgenticField(description = "Plain-language summary for agents"%s) private String agentSummary;
                @AgenticField(description = "Actions on the order") private List<OrderAction> actions;
                @AgenticField(description = "The customer") private Customer customer;
                private String customerSsn; // never annotated: on no channel
                public Long getId() { return id; }
                public String getStatus() { return status; }
                public Integer getMarginCents() { return marginCents; }
                public String getAgentSummary() { return agentSummary; }
                public List<OrderAction> getActions() { return actions; }
                public Customer getCustomer() { return customer; }
                public String getCustomerSsn() { return customerSsn; }
                public static Order sample() {
                    Order o = new Order();
                    o.id = 7L; o.status = "SHIPPED"; o.marginCents = 1234; o.agentSummary = "Shipped yesterday";
                    o.customerSsn = "123-45-6789"; o.customer = new Customer(3L, "Alice");
                    o.actions = List.of(new OrderAction(100L, "CREATED", "clerk-17", o));
                    return o;
                }
            }
            """;
    private static final String ACTION_SRC = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
            @AgenticEntity(description = "An action on an order")
            public class OrderAction {
                @AgenticField(description = "Action id") private Long id;
                @AgenticField(description = "Action type") private String type;
                @AgenticField(description = "Employee who performed it"%s) private String performedBy;
                @AgenticField(description = "Parent order") private Order order;
                public OrderAction(Long id, String type, String performedBy, Order order) {
                    this.id = id; this.type = type; this.performedBy = performedBy; this.order = order;
                }
                public Long getId() { return id; }
                public String getType() { return type; }
                public String getPerformedBy() { return performedBy; }
                public Order getOrder() { return order; }
            }
            """;
    private static final String CUSTOMER_SRC = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            @AgenticEntity(description = "A customer")
            public class Customer {
                @AgenticField(description = "Customer id") private Long id;
                @AgenticField(description = "Display name") private String name;
                public Customer(Long id, String name) { this.id = id; this.name = name; }
                public Long getId() { return id; }
                public String getName() { return name; }
            }
            """;
    /** Its own fields are on both channels; only its nested OrderAction list differs. */
    private static final String SHIPMENT_SRC = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import java.util.List;
            @AgenticEntity(description = "A shipment")
            public class Shipment {
                @AgenticField(description = "Shipment id") private Long id;
                @AgenticField(description = "Actions") private List<OrderAction> actions;
                public Long getId() { return id; }
                public List<OrderAction> getActions() { return actions; }
            }
            """;
    private static final JavaFileObject SERVICE = JavaFileObjects.forSourceString("shop.OrderService", """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
            import java.util.List;
            @AgenticExposed(description = "Order operations", returnType = Order.class)
            public class OrderService {
                @AgenticExposed(description = "Find an order on both channels")
                public Order find(Long id) { return Order.sample(); }
                @AgenticExposed(description = "List orders on both channels")
                public List<Order> list() { return List.of(Order.sample()); }
                @AgenticExposed(description = "Agent-only lookup", channels = Channel.AI)
                public Order forAgent(Long id) { return Order.sample(); }
                @AgenticExposed(description = "REST-only lookup", channels = Channel.API)
                public Order forApi(Long id) { return Order.sample(); }
                @AgenticExposed(description = "A customer", returnType = Customer.class)
                public Customer customer(Long id) { return new Customer(3L, "Alice"); }
            }
            """);

    private static final String API_ONLY = ", channels = Channel.API";
    private static final String AI_ONLY = ", channels = Channel.AI";

    private static List<JavaFileObject> sources(boolean declareEligibility) {
        String api = declareEligibility ? API_ONLY : "";
        String ai = declareEligibility ? AI_ONLY : "";
        return List.of(
                JavaFileObjects.forSourceString("shop.Order", ORDER_SRC.formatted(api, ai)),
                JavaFileObjects.forSourceString("shop.OrderAction", ACTION_SRC.formatted(api)),
                JavaFileObjects.forSourceString("shop.Customer", CUSTOMER_SRC),
                JavaFileObjects.forSourceString("shop.Shipment", SHIPMENT_SRC),
                SERVICE);
    }

    private static Compilation compile(boolean declareEligibility, String... options) {
        return javac().withProcessors(new AgenticProcessor()).withOptions((Object[]) options)
                .compile(sources(declareEligibility));
    }

    // ------------------------------------------------------------ generated types and shapes

    @Test
    void apiOnlyFieldIsOnRestAndOpenApiAndAbsentFromMcp() throws Exception {
        Compilation compilation = compile(true, FLAG_ON, PARAMETERS);
        assertThat(compilation).succeeded();
        GeneratedClasses classes = new GeneratedClasses(compilation);

        assertThat(components(classes.load("shop.generated.OrderDto")))
                .containsExactly("id", "status", "marginCents", "actions", "customer");
        assertThat(components(classes.load("shop.generated.OrderAiDto")))
                .containsExactly("id", "status", "agentSummary", "actions", "customer");

        JsonNode schemas = openApi(compilation).path("components").path("schemas");
        assertThat(fieldNames(schemas.path("OrderDto").path("properties")))
                .containsExactly("id", "status", "marginCents", "actions", "customer");
        assertThat(schemas.has("OrderAiDto")).as("the AI projection is not a REST schema").isFalse();

        JsonNode rest = JSON.valueToTree(classes.rest("find", 7L));
        assertThat(rest.has("marginCents")).isTrue();
        assertThat(rest.has("agentSummary")).isFalse();

        JsonNode mcp = classes.mcp("find", "{\"id\": 7}");
        assertThat(mcp.has("marginCents")).isFalse();
        assertThat(mcp.has("agentSummary")).isTrue();
        assertThat(mcp.path("agentSummary").asText()).isEqualTo("Shipped yesterday");
    }

    @Test
    void neverAnnotatedFieldIsOnNoChannel() throws Exception {
        Compilation compilation = compile(true, FLAG_ON, PARAMETERS);
        GeneratedClasses classes = new GeneratedClasses(compilation);

        assertThat(JSON.valueToTree(classes.rest("find", 7L)).toString()).doesNotContain("customerSsn", "123-45");
        assertThat(classes.mcp("find", "{\"id\": 7}").toString()).doesNotContain("customerSsn", "123-45");
    }

    @Test
    void sameEntityFromAnAiOnlyAndAnApiOnlyMethodGetsEachChannelsProjection() throws Exception {
        Compilation compilation = compile(true, FLAG_ON, PARAMETERS);
        GeneratedClasses classes = new GeneratedClasses(compilation);

        Class<?> tool = classes.load("shop.generated.OrderServiceMcpTool");
        Class<?> controller = classes.load("shop.generated.OrderServiceRestController");
        assertThat(methodNames(tool)).contains("forAgent").doesNotContain("forApi");
        assertThat(methodNames(controller)).contains("forApi").doesNotContain("forAgent");
        assertThat(method(tool, "forAgent").getReturnType().getSimpleName()).isEqualTo("OrderAiDto");
        assertThat(method(controller, "forApi").getReturnType().getSimpleName()).isEqualTo("OrderDto");

        assertThat(classes.mcp("forAgent", "{\"id\": 7}").has("agentSummary")).isTrue();
        assertThat(JSON.valueToTree(classes.rest("forApi", 7L)).has("marginCents")).isTrue();
    }

    @Test
    void nestedEntitiesAndCollectionsAreProjectedRecursively() throws Exception {
        Compilation compilation = compile(true, FLAG_ON, PARAMETERS);
        GeneratedClasses classes = new GeneratedClasses(compilation);

        // OrderAction.performedBy is API-only, so OrderAction splits; Shipment splits only through it
        assertThat(components(classes.load("shop.generated.OrderActionAiDto")))
                .containsExactly("id", "type", "order");
        assertThat(components(classes.load("shop.generated.ShipmentAiDto"))).containsExactly("id", "actions");
        assertThat(source(compilation, "shop.generated.ShipmentAiDto")).contains("List<OrderActionAiDto> actions");
        assertThat(source(compilation, "shop.generated.OrderAiDto"))
                .contains("List<OrderActionAiDto> actions", "CustomerDto customer");
        // Customer does not differ: both channels share CustomerDto, and no CustomerAiDto is generated
        assertThat(compilation.generatedSourceFile("shop.generated.CustomerAiDto")).isEmpty();

        JsonNode mcpAction = classes.mcp("find", "{\"id\": 7}").path("actions").get(0);
        assertThat(mcpAction.has("performedBy")).isFalse();
        assertThat(mcpAction.path("type").asText()).isEqualTo("CREATED");
        JsonNode restAction = JSON.valueToTree(classes.rest("find", 7L)).path("actions").get(0);
        assertThat(restAction.path("performedBy").asText()).isEqualTo("clerk-17");

        // A collection return is projected element by element
        JsonNode mcpList = classes.mcp("list", "{}");
        assertThat(mcpList.isArray()).isTrue();
        assertThat(mcpList.get(0).has("agentSummary")).isTrue();
        assertThat(mcpList.get(0).path("actions").get(0).has("performedBy")).isFalse();
        assertThat(((List<?>) classes.rest("list"))).hasSize(1);
    }

    @Test
    void operationWhoseChannelHasNoEligibleFieldIsAnError() {
        JavaFileObject secret = JavaFileObjects.forSourceString("shop.Secret", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
                @AgenticEntity public class Secret {
                    @AgenticField(description = "Key", channels = Channel.API) private String key;
                    public String getKey() { return key; }
                }
                """);
        JavaFileObject vault = JavaFileObjects.forSourceString("shop.Vault", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                public class Vault {
                    @AgenticExposed(description = "Read a secret", returnType = Secret.class)
                    public Secret read(String name) { return null; }
                }
                """);
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).withOptions(FLAG_ON)
                .compile(secret, vault);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining(
                "Method 'read' is exposed on the AI channel, but Secret has no field eligible for it");
    }

    // ------------------------------------------------------------ byte-identical when off

    @Test
    void flagOnWithoutEligibilityDeclaredIsByteIdenticalToFlagOff() {
        Map<String, String> off = outputs(compile(false));
        Map<String, String> on = outputs(compile(false, FLAG_ON));

        assertThat(on).isNotEmpty().isEqualTo(off);
    }

    @Test
    void flagOffIgnoresEligibilityWithAWarningAndIsByteIdentical() {
        Compilation declared = compile(true);
        assertThat(declared).succeeded();
        assertThat(declared).hadWarningContaining(
                "@AgenticField(channels) on field 'marginCents' has no effect without ai.atlas.projections=true");

        assertThat(outputs(declared)).isEqualTo(outputs(compile(false)));
    }

    // ------------------------------------------------------------ what the gate sees today

    @Test
    void contractIrAndGateDoNotSeeAFieldLeavingTheAiChannel() throws Exception {
        // baseline: marginCents on both channels; fresh: API only, so MCP responses lose it
        String baseline = ir(compile(false, FLAG_ON));
        String fresh = ir(compile(true, FLAG_ON));

        assertThat(fresh).as("the IR records no field eligibility").isEqualTo(baseline);
        List<ContractGate.Difference> differences = ContractGate.compare(
                IrJson.parse(baseline, "baseline"), IrJson.parse(fresh, "fresh"));
        assertThat(differences).as("so the gate reports nothing, not even in lock mode").isEmpty();
    }

    // ------------------------------------------------------------ characterisation of today's gaps

    @Test
    void optionalReturnWithAReturnTypeIsRejectedToday() {
        JavaFileObject finder = JavaFileObjects.forSourceString("shop.Finder", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.Optional;
                public class Finder {
                    @AgenticExposed(description = "Maybe a customer", returnType = Customer.class)
                    public Optional<Customer> maybe(Long id) { return Optional.empty(); }
                }
                """);
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .compile(JavaFileObjects.forSourceString("shop.Customer", CUSTOMER_SRC), finder);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("is not compatible with method return type");
    }

    @Test
    void entityReturnedWithoutReturnTypeReachesMcpUnfilteredToday() throws Exception {
        JavaFileObject person = JavaFileObjects.forSourceString("shop.Person", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity public class Person {
                    @AgenticField(description = "Name") private String name = "Bob";
                    private String ssn = "123-45-6789"; // not whitelisted
                    public String getName() { return name; }
                    public String getSsn() { return ssn; }
                }
                """);
        JavaFileObject people = JavaFileObjects.forSourceString("shop.OrderService", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                public class OrderService {
                    @AgenticExposed(description = "A person, no returnType declared")
                    public Person person(Long id) { return new Person(); }
                }
                """);
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).withOptions(PARAMETERS)
                .compile(person, people);
        assertThat(compilation).succeeded();

        // No DTO mapping is generated: the MCP tool returns the entity itself, and Spring AI serializes
        // it with its own ObjectMapper, which does not carry the runtime's AgentSafeModule
        assertThat(source(compilation, "shop.generated.OrderServiceMcpTool")).contains("public Person person(");
        JsonNode result = new GeneratedClasses(compilation).mcp("person", "{\"id\": 1}");
        assertThat(result.path("ssn").asText()).isEqualTo("123-45-6789");
    }

    // ------------------------------------------------------------ helpers

    private static Map<String, String> outputs(Compilation compilation) {
        assertThat(compilation).succeeded();
        Map<String, String> files = new TreeMap<>();
        for (JavaFileObject file : compilation.generatedFiles()) {
            if (file.getKind() != JavaFileObject.Kind.CLASS) {
                try {
                    files.put(file.toUri().getPath(), file.getCharContent(true).toString());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return files;
    }

    private static String ir(Compilation compilation) throws IOException {
        assertThat(compilation).succeeded();
        return compilation.generatedFile(StandardLocation.CLASS_OUTPUT, ContractIr.RESOURCE_PATH).orElseThrow()
                .getCharContent(true).toString();
    }

    private static JsonNode openApi(Compilation compilation) throws IOException {
        return JSON.readTree(compilation.generatedFile(StandardLocation.CLASS_OUTPUT,
                "META-INF/openapi/openapi-v1.json").orElseThrow().getCharContent(true).toString());
    }

    private static String source(Compilation compilation, String qualifiedName) {
        try {
            return compilation.generatedSourceFile(qualifiedName).orElseThrow().getCharContent(true).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> methodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).map(Method::getName).collect(Collectors.toList());
    }

    private static Method method(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst()
                .orElseThrow();
    }

    /** The compilation's classes, loadable; the service is instantiated once per class loader. */
    private static final class GeneratedClasses extends ClassLoader {

        private final Map<String, byte[]> bytes = new HashMap<>();

        GeneratedClasses(Compilation compilation) {
            super(ProjectionsSpikeTest.class.getClassLoader());
            for (JavaFileObject file : compilation.generatedFiles()) {
                if (file.getKind() == JavaFileObject.Kind.CLASS) {
                    String path = file.toUri().getPath();
                    String name = path.substring(path.indexOf("/CLASS_OUTPUT/") + "/CLASS_OUTPUT/".length(),
                            path.length() - ".class".length()).replace('/', '.');
                    try (InputStream in = file.openInputStream()) {
                        bytes.put(name, in.readAllBytes());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] b = bytes.get(name);
            if (b == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, b, 0, b.length);
        }

        Class<?> load(String name) throws ClassNotFoundException {
            return loadClass(name);
        }

        private Object wrapper(String name) throws Exception {
            Class<?> service = load("shop.OrderService");
            return load(name).getConstructor(service).newInstance(service.getConstructor().newInstance());
        }

        /** Calls the generated REST controller method, as Spring MVC would before serializing. */
        Object rest(String method, Object... args) throws Exception {
            Object controller = wrapper("shop.generated.OrderServiceRestController");
            return method(controller.getClass(), method).invoke(controller, args);
        }

        /** Calls the generated MCP tool through Spring AI, returning the tool result JSON. */
        JsonNode mcp(String tool, String input) throws Exception {
            Object toolObject = wrapper("shop.generated.OrderServiceMcpTool");
            ToolCallback callback = Stream.of(MethodToolCallbackProvider.builder().toolObjects(toolObject).build()
                    .getToolCallbacks()).filter(c -> c.getToolDefinition().name().equals(tool)).findFirst()
                    .orElseThrow();
            return JSON.readTree(callback.call(input));
        }
    }
}
