/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.contract.ContractGate;
import com.egoge.ai.atlas.processor.contract.ContractGate.Classification;
import com.egoge.ai.atlas.processor.contract.ContractGate.Difference;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.IrJson;
import com.egoge.ai.atlas.processor.generator.McpToolsResourceGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>Spike (Phase 5, epic #23 §9).</strong> Fixtures and helpers shared by {@link RestSpikeTest}
 * and {@link RestSpikeContractTest}: compiling with {@code ai.atlas.rest}, loading the generated
 * classes into {@link MockMvc}, and reading each operation's {@code VERB path status [locations]}
 * from the controller's Spring annotations and from the OpenAPI document.
 */
abstract class RestSpikeSupport {

    static final String REST_ON = "-Aai.atlas.rest=true";
    static final String PARAMETERS = "-parameters";
    static final String OPENAPI = "META-INF/openapi/openapi-v1.json";
    static final ObjectMapper JSON = new ObjectMapper();

    static final String ORDER = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            @AgenticEntity(description = "An order")
            public class Order {
                @AgenticField(description = "Order id") private Long id;
                @AgenticField(description = "Status") private String status;
                private String ssn;
                public Order() { }
                public Order(Long id, String status) { this.id = id; this.status = status; }
                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
                public String getStatus() { return status; }
                public void setStatus(String status) { this.status = status; }
                public String getSsn() { return ssn; }
                public void setSsn(String ssn) { this.ssn = ssn; }
            }
            """;

    /** Explicit metadata on each method, and one method left to the RPC fallback. */
    static final String ORDER_SERVICE = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import com.egoge.ai.atlas.annotations.AgenticExposed.HttpMethod;
            import com.egoge.ai.atlas.annotations.AgenticExposed.Rest;
            import java.util.*;
            @AgenticExposed(rest = @Rest(resource = "orders"))
            public class OrderService {
                public static Order lastPlaced;
                private final Map<Long, Order> orders = new TreeMap<>(Map.of(7L, new Order(7L, "NEW")));
                @AgenticExposed(description = "Order by id", returnType = Order.class,
                        rest = @Rest(method = HttpMethod.GET, path = "/{id}"))
                public Order get(Long id) { return orders.get(id); }
                @AgenticExposed(description = "Orders by status", returnType = Order.class,
                        rest = @Rest(method = HttpMethod.GET, path = ""))
                public List<Order> byStatus(String status) {
                    return orders.values().stream().filter(o -> o.getStatus().equals(status)).toList();
                }
                @AgenticExposed(description = "Place an order", returnType = Order.class,
                        rest = @Rest(method = HttpMethod.POST, path = "", status = 201))
                public Order place(Order order) { lastPlaced = order; orders.put(order.getId(), order); return order; }
                @AgenticExposed(description = "Change an order's status", returnType = Order.class,
                        rest = @Rest(method = HttpMethod.PATCH, path = "/{id}/status"))
                public Order changeStatus(Long id, String status) {
                    Order o = orders.get(id); o.setStatus(status); return o;
                }
                @AgenticExposed(description = "Cancel an order", rest = @Rest(method = HttpMethod.DELETE, path = "/{id}"))
                public void cancel(Long id) { orders.remove(id); }
                @AgenticExposed(description = "Number of orders")
                public long count() { return orders.size(); }
            }
            """;

    static final String CUSTOMER = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            @AgenticEntity(description = "A customer")
            public class Customer {
                @AgenticField(description = "Customer id") private Long id;
                @AgenticField(description = "Name") private String name;
                public Customer() { }
                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
            }
            """;

    /** The CRUD convention, one explicit override and one method no rule matches. */
    static final String CUSTOMER_SERVICE = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import com.egoge.ai.atlas.annotations.AgenticExposed.*;
            import java.util.*;
            @AgenticExposed(description = "Customers", returnType = Customer.class,
                    rest = @Rest(style = RestStyle.CRUD, resource = "customers"))
            public class CustomerService {
                private final Map<Long, Customer> store = new TreeMap<>();
                public List<Customer> findAll() { return new ArrayList<>(store.values()); }
                public Customer findById(Long id) { return store.get(id); }
                public Customer create(Customer customer) { store.put(customer.getId(), customer); return customer; }
                public Customer update(Long id, Customer customer) { customer.setId(id); store.put(id, customer); return customer; }
                public void deleteById(Long id) { store.remove(id); }
                @AgenticExposed(description = "Customer by name",
                        rest = @Rest(method = HttpMethod.GET, path = "/by-name/{name}"))
                public Customer findByName(String name) {
                    return store.values().stream().filter(c -> c.getName().equals(name)).findFirst().orElse(null);
                }
                public Customer activate(Long id) { return store.get(id); }
            }
            """;

    static String plainService() {
        return """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.List;
                @AgenticExposed(description = "Customers", returnType = Customer.class)
                public class CustomerService {
                    public List<Customer> findAll() { return null; }
                    public Customer findById(Long id) { return null; }
                    public Customer rename(Long id, String name) { return null; }
                    public void deleteById(Long id) { }
                    public Customer activate(Long id, @AgenticParam(required = Requiredness.OPTIONAL) String note) { return null; }
                }
                """;
    }

    static Compilation compile(List<String> options, String... sources) {
        List<String> all = new ArrayList<>(options);
        all.add(PARAMETERS);
        List<JavaFileObject> files = new ArrayList<>();
        for (String src : sources) {
            String pkg = src.substring(src.indexOf("package ") + 8, src.indexOf(';'));
            String name = src.replaceAll("(?s).*?public class (\\w+).*", "$1");
            files.add(source(pkg + "." + name, src));
        }
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).withOptions(all).compile(files);
        assertThat(compilation.status()).as(compilation.diagnostics().toString()).isEqualTo(Compilation.Status.SUCCESS);
        return compilation;
    }

    static Compilation compileBad(List<String> options, String classAnnotation, String member) {
        String service = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.*;
                import com.egoge.ai.atlas.annotations.AgenticParam.In;
                import java.util.List;
                %s
                public class BadService {
                    %s
                }
                """.formatted(classAnnotation == null ? "" : classAnnotation,
                member.startsWith("@") ? member : "@AgenticExposed(description = \"x\") " + member);
        List<String> all = new ArrayList<>(options);
        all.add(PARAMETERS);
        return javac().withProcessors(new AgenticProcessor()).withOptions(all)
                .compile(source("shop.Order", ORDER), source("shop.BadService", service));
    }

    static JavaFileObject source(String name, String src) {
        return JavaFileObjects.forSourceString(name, src);
    }

    static List<String> errors(Compilation compilation) {
        return compilation.diagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(null)).toList();
    }

    static ContractIr irOf(String service) throws Exception {
        return IrJson.parse(ir(compile(List.of(REST_ON), ORDER, service)), "ir");
    }

    static List<String> changes(ContractIr baseline, ContractIr fresh) {
        return ContractGate.compare(baseline, fresh).stream()
                .filter(d -> d.classification() != Classification.INFORMATIONAL)
                .map((Difference d) -> d.classification() + " " + d.change() + " " + d.before() + " -> " + d.after())
                .toList();
    }

    static String ir(Compilation compilation) throws IOException {
        return compilation.generatedFile(StandardLocation.CLASS_OUTPUT, ContractIr.RESOURCE_PATH).orElseThrow()
                .getCharContent(true).toString();
    }

    static JsonNode openApi(Compilation compilation) throws IOException {
        return JSON.readTree(compilation.generatedFile(StandardLocation.CLASS_OUTPUT, OPENAPI).orElseThrow()
                .getCharContent(true).toString());
    }

    static void assertValidOpenApi(Compilation compilation) throws IOException {
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(openApi(compilation).toString(), null, null);
        assertThat(result.getMessages()).isEmpty();
    }

    static Map<String, String> outputs(Compilation compilation) {
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

    static Map<String, String> mcpOutputs(Compilation compilation) {
        Map<String, String> files = new TreeMap<>();
        compilation.generatedSourceFiles().stream().filter(f -> f.getName().endsWith("McpTool.java"))
                .forEach(f -> files.put("shop.generated." + f.getName().replaceAll(".*/(\\w+)\\.java", "$1"), content(f)));
        compilation.generatedFile(StandardLocation.CLASS_OUTPUT, McpToolsResourceGenerator.RESOURCE_PATH)
                .ifPresent(f -> files.put(McpToolsResourceGenerator.RESOURCE_PATH, content(f)));
        return files;
    }

    static String content(JavaFileObject file) {
        try {
            return file.getCharContent(true).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String call(MockMvc mvc, RequestBuilder request) throws Exception {
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        return response.getStatus() + " " + response.getContentAsString();
    }

    /** {@code VERB path status [locations]} of each operation of the OpenAPI document, sorted. */
    static List<String> routes(JsonNode doc) {
        List<String> routes = new ArrayList<>();
        doc.path("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(op -> {
            TreeSet<String> in = new TreeSet<>();
            op.getValue().path("parameters").forEach(p -> in.add(p.path("in").asText() + ":" + p.path("name").asText()));
            if (op.getValue().has("requestBody")) {
                in.add("body");
            }
            Iterator<String> statuses = op.getValue().path("responses").fieldNames();
            String status = statuses.next();
            assertThat(statuses.hasNext()).isFalse();
            routes.add(op.getKey().toUpperCase() + " " + path.getKey() + " " + status + " " + in);
        }));
        return routes.stream().sorted().toList();
    }

    /** {@code VERB path status [locations]} of each handler method of a generated controller, sorted, from Spring's annotations. */
    static List<String> routes(Class<?> controller) {
        String base = controller.getAnnotation(RequestMapping.class).value()[0];
        List<String> routes = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            String verb = null;
            String[] value = null;
            for (Annotation a : method.getAnnotations()) {
                if (a instanceof GetMapping m) {
                    verb = "GET";
                    value = m.value();
                } else if (a instanceof PostMapping m) {
                    verb = "POST";
                    value = m.value();
                } else if (a instanceof PutMapping m) {
                    verb = "PUT";
                    value = m.value();
                } else if (a instanceof PatchMapping m) {
                    verb = "PATCH";
                    value = m.value();
                } else if (a instanceof DeleteMapping m) {
                    verb = "DELETE";
                    value = m.value();
                }
            }
            if (verb == null) {
                continue;
            }
            ResponseStatus status = method.getAnnotation(ResponseStatus.class);
            TreeSet<String> in = new TreeSet<>();
            for (Parameter p : method.getParameters()) {
                if (p.isAnnotationPresent(PathVariable.class)) {
                    in.add("path:" + p.getAnnotation(PathVariable.class).value());
                } else if (p.isAnnotationPresent(RequestBody.class)) {
                    in.add("body");
                } else if (p.isAnnotationPresent(RequestParam.class)) {
                    in.add("query:" + p.getName());
                }
            }
            routes.add(verb + " " + base + (value.length == 0 ? "" : value[0]) + " "
                    + (status != null ? status.value().value() : 200) + " " + in);
        }
        return routes.stream().sorted().toList();
    }

    /** The compilation's classes, loadable, with a controller wired to a fresh service in {@link MockMvc}. */
    static final class GeneratedClasses extends ClassLoader {

        private final Map<String, byte[]> bytes = new HashMap<>();

        GeneratedClasses(Compilation compilation) {
            super(RestSpikeSupport.class.getClassLoader());
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

        MockMvc mvc(String serviceName, String controllerName) throws Exception {
            Class<?> service = load(serviceName);
            Object controller = load(controllerName).getConstructor(service)
                    .newInstance(service.getConstructor().newInstance());
            return MockMvcBuilders.standaloneSetup(controller).build();
        }
    }
}
