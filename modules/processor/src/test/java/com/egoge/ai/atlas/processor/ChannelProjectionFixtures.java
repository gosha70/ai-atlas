/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The shop the channel projection tests compile, and the means to call its generated classes: the
 * REST controller as Spring MVC would before serializing, and the MCP tool through Spring AI's own
 * {@link MethodToolCallbackProvider}.
 */
final class ChannelProjectionFixtures {

    static final String FLAG_ON = "-Aai.atlas.projections=true";
    static final ObjectMapper JSON = new ObjectMapper();

    static final String ORDER_SRC = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
            import java.util.List;
            @AgenticEntity(description = "A customer order")
            public class Order {
                @AgenticField(description = "Order id") private Long id;
                @AgenticField(description = "Status") private String status;
                @AgenticField(description = "Internal margin"%s) private Integer marginCents;
                @AgenticField(description = "Summary for agents"%s) private String agentSummary;
                @AgenticField(description = "Actions on the order") private List<OrderAction> actions;
                @AgenticField(description = "The customer") private Customer customer;
                private String customerSsn;
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
    static final String ACTION_SRC = """
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
    static final String CUSTOMER_SRC = """
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
    /** Its own fields are on both channels; it splits only through its OrderAction iterable and array. */
    static final String SHIPMENT_SRC = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import java.util.List;
            @AgenticEntity(description = "A shipment")
            public class Shipment {
                @AgenticField(description = "Shipment id") private Long id;
                @AgenticField(description = "Actions", type = OrderAction.class) private Iterable<OrderAction> actions;
                @AgenticField(description = "History") private OrderAction[] history;
                public Long getId() { return id; }
                public Iterable<OrderAction> getActions() { return actions; }
                public OrderAction[] getHistory() { return history; }
                public static Shipment sample() {
                    Shipment s = new Shipment();
                    s.id = 5L;
                    OrderAction action = Order.sample().getActions().get(0);
                    s.actions = List.of(action);
                    s.history = new OrderAction[] { action };
                    return s;
                }
            }
            """;
    static final String SERVICE_SRC = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
            import java.util.List;
            @AgenticExposed(description = "Order operations", returnType = Order.class)
            public class OrderService {
                @AgenticExposed(description = "Find an order on both channels")
                public Order find(Long id) { return Order.sample(); }
                @AgenticExposed(description = "List orders")
                public List<Order> list() { return List.of(Order.sample()); }
                @AgenticExposed(description = "Iterate orders")
                public Iterable<Order> iterate() { return List.of(Order.sample()); }
                @AgenticExposed(description = "Orders as an array")
                public Order[] array() { return new Order[] { Order.sample() }; }
                @AgenticExposed(description = "Agent-only lookup", channels = Channel.AI)
                public Order forAgent(Long id) { return Order.sample(); }
                @AgenticExposed(description = "REST-only lookup", channels = Channel.API)
                public Order forApi(Long id) { return Order.sample(); }
                @AgenticExposed(description = "A customer", returnType = Customer.class)
                public Customer customer(Long id) { return new Customer(3L, "Alice"); }
                @AgenticExposed(description = "A shipment", returnType = Shipment.class)
                public Shipment shipment(Long id) { return Shipment.sample(); }
            }
            """;

    static final String API_ONLY = ", channels = Channel.API";
    static final String AI_ONLY = ", channels = Channel.AI";

    /** The shop, with {@code Order.marginCents} and {@code OrderAction.performedBy} API-only and {@code Order.agentSummary} AI-only, or with no eligibility declared. */
    static List<JavaFileObject> shop(boolean declared) {
        String api = declared ? API_ONLY : "";
        String ai = declared ? AI_ONLY : "";
        return List.of(
                JavaFileObjects.forSourceString("shop.Order", ORDER_SRC.formatted(api, ai)),
                JavaFileObjects.forSourceString("shop.OrderAction", ACTION_SRC.formatted(api)),
                JavaFileObjects.forSourceString("shop.Customer", CUSTOMER_SRC),
                JavaFileObjects.forSourceString("shop.Shipment", SHIPMENT_SRC),
                JavaFileObjects.forSourceString("shop.OrderService", SERVICE_SRC));
    }

    private ChannelProjectionFixtures() {
    }

    static JsonNode openApi(Compilation compilation) throws IOException {
        return JSON.readTree(compilation.generatedFile(StandardLocation.CLASS_OUTPUT,
                "META-INF/openapi/openapi-v1.json").orElseThrow().getCharContent(true).toString());
    }

    static String source(Compilation compilation, String qualifiedName) {
        try {
            return compilation.generatedSourceFile(qualifiedName).orElseThrow().getCharContent(true).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    static List<String> fieldMetadataKeys(Class<?> record) throws ReflectiveOperationException {
        return ((Map<?, ?>) record.getField("FIELD_METADATA").get(null)).keySet().stream()
                .map(String::valueOf).toList();
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    static List<String> methodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).map(Method::getName).toList();
    }

    static Method method(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst()
                .orElseThrow();
    }

    /** Generates sources in the first round, so that ai-atlas sees them only in the second. */
    static final class LaterRoundProcessor extends AbstractProcessor {

        private final Map<String, String> sources;
        private boolean generated;

        /** @param sources the source text of each type to generate, by qualified name */
        LaterRoundProcessor(Map<String, String> sources) {
            this.sources = sources;
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (generated) {
                return false;
            }
            generated = true;
            sources.forEach((name, source) -> {
                try (Writer writer = processingEnv.getFiler().createSourceFile(name).openWriter()) {
                    writer.write(source);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            return false;
        }
    }

    /** The compilation's classes, loadable, with the generated wrappers called as Spring would call them. */
    static final class GeneratedClasses extends ClassLoader {

        private static final String CLASS_OUTPUT = "/CLASS_OUTPUT/";
        private final Map<String, byte[]> bytes = new HashMap<>();

        GeneratedClasses(Compilation compilation) {
            super(ChannelProjectionFixtures.class.getClassLoader());
            for (JavaFileObject file : compilation.generatedFiles()) {
                if (file.getKind() == JavaFileObject.Kind.CLASS) {
                    String path = file.toUri().getPath();
                    String name = path.substring(path.indexOf(CLASS_OUTPUT) + CLASS_OUTPUT.length(),
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

        private Object wrapper(String name) throws ReflectiveOperationException {
            Class<?> service = load("shop.OrderService");
            return load(name).getConstructor(service).newInstance(service.getConstructor().newInstance());
        }

        /** Calls the generated REST controller method, as Spring MVC would before serializing its result. */
        Object rest(String method, Object... args) throws ReflectiveOperationException {
            Object controller = wrapper("shop.generated.OrderServiceRestController");
            return method(controller.getClass(), method).invoke(controller, args);
        }

        /** Calls the generated MCP tool through Spring AI, returning the tool result JSON. */
        JsonNode mcp(String tool, String input) throws ReflectiveOperationException, IOException {
            Object toolObject = wrapper("shop.generated.OrderServiceMcpTool");
            ToolCallback callback = Stream.of(MethodToolCallbackProvider.builder().toolObjects(toolObject).build()
                    .getToolCallbacks()).filter(c -> c.getToolDefinition().name().equals(tool)).findFirst()
                    .orElseThrow();
            return JSON.readTree(callback.call(input));
        }
    }
}
