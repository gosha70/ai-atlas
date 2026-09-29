/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.ChannelProjectionFixtures.GeneratedClasses;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.JSON;
import static com.google.testing.compile.Compiler.javac;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The shop the collection-safety tests compile, and the means to call its generated classes: the
 * MCP tool through Spring AI's own {@link MethodToolCallbackProvider}, and the REST controller
 * through MockMvc with Spring Data's {@link PageableHandlerMethodArgumentResolver} registered, as
 * Spring Boot registers it.
 */
final class CollectionsFixtures {

    static final String FLAG_ON = "-Aai.atlas.collections=true";
    static final String FLAG_OFF = "-Aai.atlas.collections=false";
    static final String STRICT = "-Aai.atlas.strict=true";
    static final String CONSTRAINTS = "-Aai.atlas.constraints=true";
    static final String PROJECTIONS = "-Aai.atlas.projections=true";
    static final String PARAMETERS = "-parameters";
    static final String NO_PAGING = "with no paging contract and no declared bound";

    static final String ORDER_SRC = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
            import java.util.List;
            import java.util.stream.LongStream;
            @AgenticEntity(description = "A customer order")
            public class Order {
                @AgenticField(description = "Order id") private Long id;
                @AgenticField(description = "Status") private String status;
                @AgenticField(description = "Internal margin"%s) private Integer marginCents = 99;
                private Integer creditScore = 700;
                public Order(Long id, String status) { this.id = id; this.status = status; }
                public Long getId() { return id; }
                public String getStatus() { return status; }
                public Integer getMarginCents() { return marginCents; }
                public Integer getCreditScore() { return creditScore; }
                /** Five orders: every result set of the fixture. */
                public static List<Order> all() {
                    return LongStream.rangeClosed(1, 5).mapToObj(i -> new Order(i, "NEW")).toList();
                }
            }
            """;

    /**
     * Every paging style. {@code byStatus} allow-lists two sort properties, {@code recent} declares
     * a page-size ceiling of 3, {@code top} a bound of 2 that the service breaks by returning five.
     */
    static final String SERVICE_SRC = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
            import jakarta.validation.constraints.Max;
            import org.springframework.data.domain.*;
            import java.util.List;
            @AgenticExposed(description = "Order operations", returnType = Order.class)
            public class OrderService {
                /** The last Pageable the service received, to prove what the wrappers pass. */
                public static Pageable lastPageable;
                private static <T> List<T> slice(List<T> all, Pageable pageable) {
                    lastPageable = pageable;
                    if (pageable.isUnpaged()) {
                        return all;
                    }
                    int from = (int) Math.min(pageable.getOffset(), all.size());
                    return all.subList(from, Math.min(from + pageable.getPageSize(), all.size()));
                }
                @AgenticExposed(description = "Orders in a status, a page at a time")
                public Page<Order> byStatus(String status, @AgenticParam(sortable = {"id", "status"}) Pageable pageable) {
                    return new PageImpl<>(slice(Order.all(), pageable), pageable, Order.all().size());
                }
                @AgenticExposed(description = "Recent orders, a slice at a time", maxResults = 3)
                public Slice<Order> recent(Pageable pageable) {
                    List<Order> content = slice(Order.all(), pageable);
                    return new SliceImpl<>(content, pageable, pageable.getOffset() + content.size() < 5);
                }
                @AgenticExposed(description = "Newest orders")
                public List<Order> newest(Pageable pageable) { return slice(Order.all(), pageable); }
                @AgenticExposed(description = "Every order")
                public List<Order> list() { return Order.all(); }
                @AgenticExposed(description = "The top orders", maxResults = 2)
                public List<Order> top() { return Order.all(); }
                @AgenticExposed(description = "Search orders")
                public List<Order> search(String text, @AgenticParam(paging = Paging.LIMIT) @Max(3) int limit,
                                          @AgenticParam(paging = Paging.CURSOR) String after) {
                    return Order.all().subList(after == null ? 0 : 1, Math.min(limit, 4));
                }
                @AgenticExposed(description = "Orders after a cursor")
                public List<Order> after(@AgenticParam(paging = Paging.CURSOR) String cursor) { return Order.all(); }
                @AgenticExposed(description = "Archived orders")
                public Page<Order> archived() { return new PageImpl<>(Order.all()); }
                @AgenticExposed(description = "Export every order", channels = Channel.API)
                public List<Order> export() { return Order.all(); }
                @AgenticExposed(description = "One order")
                public Order find(Long id) { return Order.all().get(0); }
            }
            """;

    private CollectionsFixtures() {
    }

    static List<JavaFileObject> shop(String marginChannels) {
        return List.of(JavaFileObjects.forSourceString("shop.Order", ORDER_SRC.formatted(marginChannels)),
                JavaFileObjects.forSourceString("shop.OrderService", SERVICE_SRC));
    }

    /** Compiles the shop, every field on every channel, with {@code -parameters} and the options. */
    static Compilation compile(String... options) {
        return compile(List.of(), options);
    }

    static Compilation compile(List<JavaFileObject> extra, String... options) {
        List<String> all = new ArrayList<>(List.of(options));
        all.add(PARAMETERS);
        List<JavaFileObject> sources = new ArrayList<>(shop(""));
        sources.addAll(extra);
        return javac().withProcessors(new AgenticProcessor()).withOptions(all).compile(sources);
    }

    /** Compiles one source on its own, beside the shop's {@code Order}. */
    static Compilation compileWithOrder(String qualifiedName, String source, String... options) {
        List<String> all = new ArrayList<>(List.of(options));
        all.add(PARAMETERS);
        return javac().withProcessors(new AgenticProcessor()).withOptions(all).compile(
                JavaFileObjects.forSourceString("shop.Order", ORDER_SRC.formatted("")),
                JavaFileObjects.forSourceString(qualifiedName, source));
    }

    static List<String> messages(Compilation compilation, Diagnostic.Kind kind) {
        return compilation.diagnostics().stream().filter(d -> d.getKind() == kind)
                .map(d -> d.getMessage(null)).toList();
    }

    static String resource(Compilation compilation, String path) throws IOException {
        return compilation.generatedFile(StandardLocation.CLASS_OUTPUT, path).orElseThrow()
                .getCharContent(true).toString();
    }

    static JsonNode tool(JsonNode mcpTools, String name) {
        return StreamSupport.stream(mcpTools.path("tools").spliterator(), false)
                .filter(t -> name.equals(t.path("name").asText())).findFirst().orElseThrow();
    }

    static ToolCallback callback(GeneratedClasses classes, String name) throws ReflectiveOperationException {
        Class<?> service = classes.load("shop.OrderService");
        Object tool = classes.load("shop.generated.OrderServiceMcpTool").getConstructor(service)
                .newInstance(service.getConstructor().newInstance());
        return Stream.of(MethodToolCallbackProvider.builder().toolObjects(tool).build().getToolCallbacks())
                .filter(c -> c.getToolDefinition().name().equals(name)).findFirst().orElseThrow();
    }

    static Pageable pageRequested(GeneratedClasses classes) throws ReflectiveOperationException {
        return (Pageable) classes.load("shop.OrderService").getField("lastPageable").get(null);
    }

    /** POSTs to the generated controller through Spring MVC, with Spring Data's Pageable resolver registered. */
    static MockHttpServletResponse rest(GeneratedClasses classes, String path, String... params) throws Exception {
        Class<?> service = classes.load("shop.OrderService");
        Object controller = classes.load("shop.generated.OrderServiceRestController").getConstructor(service)
                .newInstance(service.getConstructor().newInstance());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
        var request = post("/api/v1/order-service" + path);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    static JsonNode restJson(GeneratedClasses classes, String path, String... params) throws Exception {
        MockHttpServletResponse response = rest(classes, path, params);
        if (response.getStatus() != 200) {
            throw new AssertionError("HTTP " + response.getStatus() + ": " + response.getErrorMessage());
        }
        return JSON.readTree(response.getContentAsString());
    }
}
