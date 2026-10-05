/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.egoge.ai.atlas.processor.RestTestSupport.ORDER;
import static com.egoge.ai.atlas.processor.RestTestSupport.REST_ON;
import static com.egoge.ai.atlas.processor.RestTestSupport.compile;
import static com.egoge.ai.atlas.processor.RestTestSupport.compileUnchecked;
import static com.egoge.ai.atlas.processor.RestTestSupport.errors;
import static com.egoge.ai.atlas.processor.RestTestSupport.openApi;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Request bodies: an entity's input record creates it only through a constructor whose parameters
 * are named as its fields, so no two values can be swapped; and the OpenAPI document describes
 * every other accepted body in the shape Jackson binds.
 */
class RestRequestBodyTest {

    @Test
    void aConstructorTakingTheFieldsTypesInAnotherOrderIsNotUsed() {
        String person = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity(description = "A person")
                public class Person {
                    @AgenticField(description = "First name") private final String first;
                    @AgenticField(description = "Last name") private final String last;
                    public Person(String last, String first) { this.first = first; this.last = last; }
                    public String getFirst() { return first; }
                    public String getLast() { return last; }
                }
                """;
        String service = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.*;
                @AgenticExposed(rest = @Rest(resource = "people"))
                public class PersonService {
                    @AgenticExposed(description = "Add", rest = @Rest(method = HttpMethod.POST, path = ""))
                    public void add(Person person) { }
                }
                """;
        Compilation compilation = compileUnchecked(List.of(REST_ON), person, service);

        assertThat(errors(compilation)).anyMatch(e -> e.contains("The @AgenticEntity Person is a request body, but its"
                + " input record PersonInput cannot create it")
                && e.contains("or an accessible constructor taking (java.lang.String first, java.lang.String last) in"
                        + " that order, each parameter named as its field"));
    }

    @Test
    void openApiDescribesEachAcceptedBodyInTheShapeJacksonBinds() throws Exception {
        String service = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.*;
                import com.egoge.ai.atlas.annotations.AgenticParam.In;
                import java.math.*;
                import java.util.*;
                @AgenticExposed(rest = @Rest(resource = "things"))
                public class ThingService {
                    public enum Priority { LOW, HIGH }
                    public record Note(String text) { }
                    public static class Box<T> { public T value; }
                    @AgenticExposed(description = "a", rest = @Rest(method = HttpMethod.POST, path = "/a"))
                    public void a(@AgenticParam(in = In.BODY) Priority body) { }
                    @AgenticExposed(description = "b", rest = @Rest(method = HttpMethod.POST, path = "/b"))
                    public void b(@AgenticParam(in = In.BODY) List<Priority> body) { }
                    @AgenticExposed(description = "c", rest = @Rest(method = HttpMethod.POST, path = "/c"))
                    public void c(@AgenticParam(in = In.BODY) List<UUID> body) { }
                    @AgenticExposed(description = "d", rest = @Rest(method = HttpMethod.POST, path = "/d"))
                    public void d(@AgenticParam(in = In.BODY) BigDecimal[] body) { }
                    @AgenticExposed(description = "e", rest = @Rest(method = HttpMethod.POST, path = "/e"))
                    public void e(@AgenticParam(in = In.BODY) Set<Long> body) { }
                    @AgenticExposed(description = "f", rest = @Rest(method = HttpMethod.POST, path = "/f"))
                    public void f(@AgenticParam(in = In.BODY) List<Note> body) { }
                    @AgenticExposed(description = "g", rest = @Rest(method = HttpMethod.POST, path = "/g"))
                    public void g(@AgenticParam(in = In.BODY) Box<String> body) { }
                    @SuppressWarnings("rawtypes")
                    @AgenticExposed(description = "h", rest = @Rest(method = HttpMethod.POST, path = "/h"))
                    public void h(@AgenticParam(in = In.BODY) List body) { }
                    @AgenticExposed(description = "i", rest = @Rest(method = HttpMethod.POST, path = "/i"))
                    public void i(@AgenticParam(in = In.BODY) Character body) { }
                    @AgenticExposed(description = "j", rest = @Rest(method = HttpMethod.POST, path = "/j"))
                    public void j(@AgenticParam(in = In.BODY) short body) { }
                    @AgenticExposed(description = "k", rest = @Rest(method = HttpMethod.POST, path = "/k"))
                    public void k(@AgenticParam(in = In.BODY) List<Byte> body) { }
                    @AgenticExposed(description = "l", rest = @Rest(method = HttpMethod.POST, path = "/l"))
                    public void l(@AgenticParam(in = In.BODY) Optional<Integer> body) { }
                    @AgenticExposed(description = "m", rest = @Rest(method = HttpMethod.POST, path = "/m"))
                    public void m(@AgenticParam(in = In.BODY) Optional<List<UUID>> body) { }
                    @AgenticExposed(description = "n", rest = @Rest(method = HttpMethod.POST, path = "/n"))
                    public void n(@AgenticParam(in = In.BODY) OptionalLong body) { }
                    @AgenticExposed(description = "o", rest = @Rest(method = HttpMethod.POST, path = "/o"))
                    public void o(@AgenticParam(in = In.BODY) BigInteger body) { }
                    @AgenticExposed(description = "p", rest = @Rest(method = HttpMethod.POST, path = "/p"))
                    public void p(@AgenticParam(in = In.BODY) Optional<List<BigInteger>> body) { }
                    @AgenticExposed(description = "q", rest = @Rest(method = HttpMethod.POST, path = "/q"))
                    public void q(@AgenticParam(in = In.BODY) List<List<UUID>> body) { }
                    @AgenticExposed(description = "r", rest = @Rest(method = HttpMethod.POST, path = "/r"))
                    public void r(@AgenticParam(in = In.BODY) UUID[][] body) { }
                    @AgenticExposed(description = "s", rest = @Rest(method = HttpMethod.POST, path = "/s"))
                    public void s(@AgenticParam(in = In.BODY) Optional<List<Optional<Priority>>> body) { }
                    @AgenticExposed(description = "t", rest = @Rest(method = HttpMethod.POST, path = "/t"))
                    public void t(@AgenticParam(in = In.BODY) Tree body) { }
                    public static class Tree extends ArrayList<Tree> { }
                }
                """;
        Compilation compilation = compile(List.of(REST_ON), ORDER, service);
        RestTestSupport.assertValidOpenApi(compilation);
        JsonNode paths = openApi(compilation).path("paths");

        assertThat(body(paths, "a")).isEqualTo("{\"type\":\"string\",\"enum\":[\"LOW\",\"HIGH\"]}");
        assertThat(body(paths, "b"))
                .isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"LOW\",\"HIGH\"]}}");
        assertThat(body(paths, "c")).isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}");
        assertThat(body(paths, "d")).isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"number\"}}");
        assertThat(body(paths, "e"))
                .isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"integer\",\"format\":\"int64\"}}");
        assertThat(body(paths, "f")).isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"object\"}}");
        // A generic type that is not a collection is an object, not an array of its type argument
        assertThat(body(paths, "g")).isEqualTo("{\"type\":\"object\"}");
        assertThat(body(paths, "h")).isEqualTo("{\"type\":\"array\",\"items\":{}}");
        assertThat(body(paths, "i")).isEqualTo("{\"type\":\"string\"}");
        // Every integral kind is an integer, byte and short included
        assertThat(body(paths, "j")).isEqualTo("{\"type\":\"integer\"}");
        assertThat(body(paths, "k")).isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}");
        // An Optional has the shape of the value Spring and Jackson unwrap
        assertThat(body(paths, "l")).isEqualTo("{\"type\":\"integer\",\"format\":\"int32\"}");
        assertThat(body(paths, "m")).isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}");
        assertThat(body(paths, "n")).isEqualTo("{\"type\":\"integer\",\"format\":\"int64\"}");
        assertThat(body(paths, "o")).isEqualTo("{\"type\":\"integer\"}");
        assertThat(body(paths, "p")).isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}");
        // Nested containers keep every level's shape
        String uuids = "{\"type\":\"array\",\"items\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}";
        assertThat(body(paths, "q")).isEqualTo(uuids);
        assertThat(body(paths, "r")).isEqualTo(uuids);
        assertThat(body(paths, "s"))
                .isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"LOW\",\"HIGH\"]}}");
        // A collection of itself ends in an object instead of nesting without end
        assertThat(body(paths, "t")).isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"object\"}}");
    }

    private static String body(JsonNode paths, String operation) {
        return paths.path("/api/v1/things/" + operation).path("post").path("requestBody").path("content")
                .path("application/json").path("schema").toString();
    }
}
