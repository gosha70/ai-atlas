/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.tools.Diagnostic;
import java.util.List;
import java.util.stream.Stream;

import static com.egoge.ai.atlas.processor.RestTestSupport.ORDER;
import static com.egoge.ai.atlas.processor.RestTestSupport.REST_OFF;
import static com.egoge.ai.atlas.processor.RestTestSupport.REST_ON;
import static com.egoge.ai.atlas.processor.RestTestSupport.compileService;
import static com.egoge.ai.atlas.processor.RestTestSupport.compileUnchecked;
import static com.egoge.ai.atlas.processor.RestTestSupport.errors;
import static com.egoge.ai.atlas.processor.RestTestSupport.messages;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ai.atlas.rest}: every invalid REST declaration fails the compilation with an ERROR naming
 * the declaration; routes that collide, variable names aside, are an ERROR on each method naming
 * the others; REST metadata on a method off the API channel is a WARNING; a literal route beside a
 * variable one is a NOTE; and any REST declaration while the option is off is an ERROR naming the
 * route still served.
 */
class RestValidationTest {

    private static final String CRUD = "@AgenticExposed(rest = @Rest(style = RestStyle.CRUD))";

    static Stream<Arguments> invalidDeclarations() {
        return Stream.of(
                Arguments.of("malformed path", null,
                        "@AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = \"no-slash\")) public void a() { }",
                        "@Rest(path = \"no-slash\") on 'a' must be empty or '/'-separated segments"),
                Arguments.of("regex variable", null,
                        "@AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = \"/{id:\\\\d+}\")) public void a(Long id) { }",
                        "must be empty or '/'-separated segments"),
                Arguments.of("variable naming no parameter", null,
                        "@AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = \"/{id}\")) public void a() { }",
                        "Path variable {id} of 'a' names no parameter"),
                Arguments.of("repeated variable", null,
                        "@AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = \"/{id}/{id}\")) public void a(Long id) { }",
                        "Path variable {id} of 'a' appears more than once in GET /api/v1/bad-service/{id}/{id}"),
                Arguments.of("PATH without a variable", null,
                        "@AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = \"\")) public void a(@AgenticParam(in = In.PATH) Long id) { }",
                        "Parameter 'id' of 'a' is in the path, but GET /api/v1/bad-service has no {id}"),
                Arguments.of("variable's parameter declared elsewhere", null,
                        "@AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = \"/{id}\")) public void a(@AgenticParam(in = In.QUERY) Long id) { }",
                        "Parameter 'id' of 'a' is named by the path variable {id} but declared in the query"),
                Arguments.of("non-scalar path parameter", null,
                        "@AgenticExposed(rest = @Rest(method = HttpMethod.PUT, path = \"/{ids}\")) public void a(List<Long> ids) { }",
                        "Path parameter 'ids' of 'a' must be a scalar"),
                Arguments.of("two bodies", null,
                        "@AgenticExposed(rest = @Rest(method = HttpMethod.POST, path = \"\")) public void a(Order x, @AgenticParam(in = In.BODY) Integer y) { }",
                        "'a' has 2 body parameters — at most one parameter may be in the body"),
                Arguments.of("body on GET", null,
                        "@AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = \"\")) public void a(Order order) { }",
                        "GET 'a' must not have a request body"),
                Arguments.of("body on DELETE", null,
                        "@AgenticExposed(rest = @Rest(method = HttpMethod.DELETE, path = \"\")) public void a(@AgenticParam(in = In.BODY) Integer n) { }",
                        "DELETE 'a' must not have a request body"),
                Arguments.of("body on the RPC mapping", null,
                        "public void a(@AgenticParam(in = In.BODY) Integer n) { }",
                        "Parameter 'n' of 'a' is declared in the body, but 'a' is on the RPC mapping"),
                Arguments.of("String body", null,
                        "@AgenticExposed(rest = @Rest(method = HttpMethod.POST, path = \"\")) public void a(@AgenticParam(in = In.BODY) String note) { }",
                        "The request body 'note' of 'a' is a java.lang.String, which Spring reads as raw text, not JSON"),
                Arguments.of("collection of entities as a body", null,
                        "@AgenticExposed(rest = @Rest(method = HttpMethod.POST, path = \"\")) public void a(@AgenticParam(in = In.BODY) List<Order> orders) { }",
                        "is java.util.List<shop.Order>, a collection of the @AgenticEntity Order"),
                Arguments.of("non-2xx status", null,
                        "@AgenticExposed(rest = @Rest(status = 404)) public void a() { }",
                        "@Rest(status = 404) on 'a' must be a 2xx success status"),
                Arguments.of("unnamed 2xx status", null,
                        "@AgenticExposed(rest = @Rest(status = 299)) public void a() { }",
                        "@Rest(status = 299) on 'a' must be a 2xx success status"),
                Arguments.of("204 with a body", null,
                        "@AgenticExposed(rest = @Rest(status = 204)) public long a() { return 0; }",
                        "'a' returns long but its status is 204 No Content, which carries no body"),
                Arguments.of("204 on a CRUD delete returning a value, declared", CRUD,
                        "@AgenticExposed(rest = @Rest(status = 204)) public boolean delete(Long id) { return true; }",
                        "'delete' returns boolean but its status is 204 No Content"),
                Arguments.of("method-level attributes on a class",
                        "@AgenticExposed(rest = @Rest(method = HttpMethod.GET))", "public void a() { }",
                        "@Rest(method, path, status) on class BadService — declare them on each method"),
                Arguments.of("class-level attributes on a method", null,
                        "@AgenticExposed(rest = @Rest(resource = \"things\")) public void a() { }",
                        "@Rest(style, resource) on method 'a' — declare them on the service class"),
                Arguments.of("multi-segment resource", "@AgenticExposed(rest = @Rest(resource = \"a/b\"))",
                        "public void a() { }",
                        "@Rest(resource = \"a/b\") on BadService must be one path segment"),
                Arguments.of("'..' resource", "@AgenticExposed(rest = @Rest(resource = \"..\"))",
                        "public void a() { }",
                        "@Rest(resource = \"..\") on BadService must be one path segment of letters, digits, '.', '_',"
                                + " '~' or '-', and neither '.' nor '..'"),
                Arguments.of("'.' resource", "@AgenticExposed(rest = @Rest(resource = \".\"))",
                        "public void a() { }",
                        "@Rest(resource = \".\") on BadService must be one path segment"),
                Arguments.of("'..' path segment", null,
                        "@AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = \"/../admin\")) public void a() { }",
                        "@Rest(path = \"/../admin\") on 'a' has a '.' or '..' segment, which would leave the service's"
                                + " resource or the version prefix"),
                Arguments.of("'.' path segment", null,
                        "@AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = \"/.\")) public void a() { }",
                        "@Rest(path = \"/.\") on 'a' has a '.' or '..' segment"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDeclarations")
    void anInvalidDeclarationIsACompileError(String name, String classAnnotation, String member, String message) {
        Compilation compilation = compileService(List.of(REST_ON), classAnnotation, member);

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(errors(compilation)).anyMatch(e -> e.contains(message));
    }

    @Test
    void routesThatDifferOnlyByVariableNamesCollideOnEachMethodNamingTheOthers() {
        Compilation compilation = compileService(List.of(REST_ON), "@AgenticExposed(rest = @Rest(resource = \"orders\"))", """
                @AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = "/{id}")) public void a(Long id) { }
                    @AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = "/{number}")) public void b(Long number) { }
                    @AgenticExposed(rest = @Rest(method = HttpMethod.POST, path = "/activate")) public void c(Long id) { }
                    public void activate(Long id) { }""");

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(errors(compilation))
                .anyMatch(e -> e.contains("REST mapping GET /api/v1/orders/{id} of shop.BadService#a(Long) is also mapped by"
                        + " shop.BadService#b(Long) (GET /api/v1/orders/{number})"))
                .anyMatch(e -> e.contains("REST mapping GET /api/v1/orders/{number} of shop.BadService#b(Long) is also mapped"
                        + " by shop.BadService#a(Long) (GET /api/v1/orders/{id})"))
                .anyMatch(e -> e.contains("REST mapping POST /api/v1/orders/activate of shop.BadService#c(Long) is also"
                        + " mapped by shop.BadService#activate(Long)"))
                .anyMatch(e -> e.contains("REST mapping POST /api/v1/orders/activate of shop.BadService#activate(Long) is"
                        + " also mapped by shop.BadService#c(Long)"));
    }

    @Test
    void overloadsSharingARouteAreToldApartByTheirParameterTypes() {
        Compilation compilation = compileService(List.of(REST_ON),
                "@AgenticExposed(rest = @Rest(style = RestStyle.CRUD, resource = \"orders\"))", """
                public void findById(Long id) { }
                    @AgenticExposed(description = "y", toolName = "findByKey") public void findById(String id) { }""");

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(errors(compilation))
                .anyMatch(e -> e.contains("REST mapping GET /api/v1/orders/{id} of shop.BadService#findById(Long) is"
                        + " also mapped by shop.BadService#findById(String)"))
                .anyMatch(e -> e.contains("REST mapping GET /api/v1/orders/{id} of shop.BadService#findById(String) is"
                        + " also mapped by shop.BadService#findById(Long)"));
    }

    @Test
    void aStatusAloneMovingAnEntityToTheBodyIsAWarning() {
        Compilation moved = compileService(List.of(REST_ON), null,
                "@AgenticExposed(rest = @Rest(status = 202)) public void a(Order order) { }");

        assertThat(moved.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(messages(moved, Diagnostic.Kind.WARNING)).anyMatch(w -> w.contains(
                "Parameter 'order' of 'a' moves from the query to the request body: @Rest(status) alone makes"
                        + " POST /api/v1/bad-service/a an explicit mapping, where an @AgenticEntity is the body"));

        for (String member : List.of(
                "@AgenticExposed(rest = @Rest(status = 202)) public void a(@AgenticParam(in = In.BODY) Order order) { }",
                "@AgenticExposed(rest = @Rest(method = HttpMethod.POST, path = \"\", status = 202)) public void a(Order order) { }",
                "@AgenticExposed(rest = @Rest(status = 202)) public void a(Long id) { }")) {
            assertThat(messages(compileService(List.of(REST_ON), null, member), Diagnostic.Kind.WARNING))
                    .as(member).noneMatch(w -> w.contains("moves from the query to the request body"));
        }
    }

    @Test
    void routesThatMatchTheSameRequestsWithNeitherMoreSpecificAreAnError() {
        Compilation compilation = compileService(List.of(REST_ON), "@AgenticExposed(rest = @Rest(resource = \"orders\"))", """
                @AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = "/{id}/items")) public void a(Long id) { }
                    @AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = "/open/{kind}")) public void b(String kind) { }""");

        assertThat(errors(compilation))
                .anyMatch(e -> e.contains("REST mapping GET /api/v1/orders/{id}/items of shop.BadService#a(Long) matches the"
                        + " same requests as GET /api/v1/orders/open/{kind} of shop.BadService#b(String), and neither is more"
                        + " specific"))
                .anyMatch(e -> e.contains("REST mapping GET /api/v1/orders/open/{kind} of shop.BadService#b(String) matches"));
    }

    @Test
    void aLiteralRouteBesideAVariableOneIsANote() {
        Compilation compilation = compileService(List.of(REST_ON),
                "@AgenticExposed(rest = @Rest(style = RestStyle.CRUD, resource = \"orders\"))", """
                public void findById(Long id) { }
                    @AgenticExposed(rest = @Rest(method = HttpMethod.GET, path = "/active")) public void active() { }""");

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(messages(compilation, Diagnostic.Kind.NOTE)).anyMatch(n -> n.contains(
                "REST mapping GET /api/v1/orders/active of shop.BadService#active() has a literal segment where"
                        + " GET /api/v1/orders/{id} of shop.BadService#findById(Long) has a variable. Spring routes a"
                        + " matching request to the literal one"));
    }

    @Test
    void restMetadataOnAMethodOffTheApiChannelIsAWarning() {
        Compilation compilation = compileService(List.of(REST_ON), null, """
                @AgenticExposed(description = "x", channels = Channel.AI, rest = @Rest(method = HttpMethod.GET))
                    public void a() { }
                    @AgenticExposed(description = "y", channels = Channel.AI) public void b(@AgenticParam(in = In.QUERY) Long id) { }""");

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(messages(compilation, Diagnostic.Kind.WARNING))
                .anyMatch(w -> w.contains("REST metadata on 'a' has no effect: it is not on the API channel"))
                .anyMatch(w -> w.contains("REST metadata on 'b' has no effect: it is not on the API channel"));
    }

    static Stream<Arguments> declarationsWhileOff() {
        return Stream.of(
                Arguments.of(null, "@AgenticExposed(rest = @Rest(method = HttpMethod.DELETE, path = \"/{id}\")) public void a(Long id) { }",
                        "REST metadata on 'a' requires ai.atlas.rest=true. Without it the operation is still served at"
                                + " POST /api/v1/bad-service/a"),
                Arguments.of(CRUD, "public void deleteById(Long id) { }",
                        "REST metadata on 'deleteById' requires ai.atlas.rest=true. Without it the operation is still"
                                + " served at POST /api/v1/bad-service/delete-by-id"),
                Arguments.of(null, "public void a(@AgenticParam(in = In.QUERY) Long id) { }",
                        "REST metadata on 'a' requires ai.atlas.rest=true. Without it the operation is still served at"
                                + " POST /api/v1/bad-service/a"),
                Arguments.of(null, "@AgenticExposed(description = \"x\", channels = Channel.AI, rest = @Rest(status = 201))"
                                + " public long a() { return 0; }",
                        "REST metadata on 'a' requires ai.atlas.rest=true, and has no effect: 'a' is not on the API"
                                + " channel"));
    }

    @ParameterizedTest
    @MethodSource("declarationsWhileOff")
    void aRestDeclarationWhileTheOptionIsOffIsAnErrorNamingTheRouteStillServed(String classAnnotation, String member,
                                                                                String message) {
        for (List<String> off : List.of(List.<String>of(), List.of(REST_OFF))) {
            Compilation compilation = compileService(off, classAnnotation, member);

            assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
            assertThat(errors(compilation)).anyMatch(e -> e.contains(message));
        }
    }

    @Test
    void anInvalidOptionValueIsAnError() {
        Compilation compilation = compileUnchecked(List.of("-Aai.atlas.rest=yes"), ORDER);

        assertThat(errors(compilation)).anyMatch(e -> e.contains("ai.atlas.rest must be 'true' or 'false'. Got: yes"));
    }
}
