/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import java.util.List;

import static com.egoge.ai.atlas.processor.CollectionsFixtures.CONSTRAINTS;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.FLAG_OFF;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.FLAG_ON;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.NO_PAGING;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.STRICT;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.compile;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.compileWithOrder;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.messages;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code ai.atlas.collections} diagnostics: every declaration is an ERROR with the flag off and
 * an exposed {@code Pageable} a WARNING; with it on, an unbounded collection is a WARNING, an ERROR
 * under strict for the AI channel only, and every misuse is an ERROR.
 */
class CollectionsDiagnosticsTest {

    /** A service declaring nothing: one unbounded list, one Pageable. */
    private static final String PLAIN_SRC = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import org.springframework.data.domain.*;
            import java.util.List;
            @AgenticExposed(description = "Plain", returnType = Order.class)
            public class Plain {
                @AgenticExposed(description = "Every order")
                public List<Order> every() { return Order.all(); }
                @AgenticExposed(description = "A page")
                public Page<Order> paged(Pageable pageable) { return Page.empty(); }
            }
            """;

    // ================================================================ flag off

    @Test
    void theOptionMustBeABoolean() {
        Compilation compilation = compileWithOrder("shop.Plain", PLAIN_SRC, "-Aai.atlas.collections=yes");

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("ai.atlas.collections must be 'true' or 'false'. Got: yes");
    }

    @Test
    void withTheFlagOffEveryDeclarationIsAnError() {
        Compilation compilation = compile(FLAG_OFF);

        assertThat(compilation).failed();
        List<String> errors = messages(compilation, Diagnostic.Kind.ERROR);
        assertThat(errors).filteredOn(m -> m.contains("requires ai.atlas.collections=true")).containsExactlyInAnyOrder(
                "[ai-atlas] @AgenticParam(sortable) on parameter 'pageable' of shop.OrderService#byStatus requires"
                        + " ai.atlas.collections=true; with it off the declaration would silently do nothing",
                "[ai-atlas] @AgenticExposed(maxResults) on shop.OrderService#recent requires"
                        + " ai.atlas.collections=true; with it off the declaration would silently do nothing",
                "[ai-atlas] @AgenticExposed(maxResults) on shop.OrderService#bulk requires"
                        + " ai.atlas.collections=true; with it off the declaration would silently do nothing",
                "[ai-atlas] @AgenticExposed(maxResults) on shop.OrderService#top requires"
                        + " ai.atlas.collections=true; with it off the declaration would silently do nothing",
                "[ai-atlas] @AgenticParam(paging) on parameter 'limit' of shop.OrderService#search requires"
                        + " ai.atlas.collections=true; with it off the declaration would silently do nothing",
                "[ai-atlas] @AgenticParam(paging) on parameter 'after' of shop.OrderService#search requires"
                        + " ai.atlas.collections=true; with it off the declaration would silently do nothing",
                "[ai-atlas] @AgenticParam(paging) on parameter 'cursor' of shop.OrderService#after requires"
                        + " ai.atlas.collections=true; with it off the declaration would silently do nothing");
    }

    @Test
    void withTheFlagOffAnExposedPageableIsAWarningAndNothingElseIsReported() {
        Compilation compilation = compileWithOrder("shop.Plain", PLAIN_SRC, STRICT);

        assertThat(compilation).succeeded();
        assertThat(messages(compilation, Diagnostic.Kind.WARNING)).filteredOn(m -> m.contains("Pageable"))
                .containsExactly("[ai-atlas] shop.Plain#paged takes a Spring Data Pageable, which its generated"
                        + " wrappers cannot bind with ai.atlas.collections off: the REST endpoint answers 400 and"
                        + " the MCP tool cannot be called. Set ai.atlas.collections=true");
        assertThat(messages(compilation, Diagnostic.Kind.WARNING)).noneMatch(m -> m.contains(NO_PAGING));
    }

    @Test
    void aClassLevelBoundIsAnErrorOnceWhateverTheFlag() {
        String source = PLAIN_SRC.replace("returnType = Order.class)", "returnType = Order.class, maxResults = 5)");
        for (String flag : List.of(FLAG_ON, FLAG_OFF)) {
            Compilation compilation = compileWithOrder("shop.Plain", source, flag);

            assertThat(compilation).failed();
            assertThat(messages(compilation, Diagnostic.Kind.ERROR)).filteredOn(m -> m.contains("not the class"))
                    .containsExactly("[ai-atlas] @AgenticExposed(maxResults) on shop.Plain must be declared on each"
                            + " method, not the class: a bound describes one operation's result");
        }
    }

    // ================================================================ flag on: unbounded collections

    @Test
    void anUnboundedCollectionIsAWarningNamingTheMethodReturnAndChannels() {
        Compilation compilation = compile(FLAG_ON);

        assertThat(compilation).succeeded();
        List<String> unbounded = messages(compilation, Diagnostic.Kind.WARNING).stream()
                .filter(m -> m.contains(NO_PAGING)).toList();
        assertThat(unbounded).hasSize(4)
                .anyMatch(m -> m.equals("[ai-atlas] shop.OrderService#list returns java.util.List<shop.Order> on"
                        + " channels [AI, API] with no paging contract and no declared bound, so one call can return"
                        + " the whole result set. Take a Spring Data Pageable, mark a limit the service honours with"
                        + " @AgenticParam(paging = LIMIT), or declare @AgenticExposed(maxResults = N) when the result"
                        + " is known to be small"))
                .anyMatch(m -> m.contains("#after") && m.contains("Its CURSOR parameter bounds nothing without a LIMIT"))
                // A Page without a Pageable cannot be paged by clients: it needs a bound like any collection
                .anyMatch(m -> m.contains("#archived returns org.springframework.data.domain.Page<shop.Order>"))
                .anyMatch(m -> m.contains("#export") && m.contains("on channels [API]"));
    }

    @Test
    void strictModeMakesAnUnboundedCollectionAnErrorOnTheAiChannelOnly() {
        Compilation compilation = compile(FLAG_ON, STRICT);

        assertThat(compilation).failed();
        assertThat(messages(compilation, Diagnostic.Kind.ERROR)).filteredOn(m -> m.contains(NO_PAGING))
                .hasSize(3).noneMatch(m -> m.contains("#export"));
        // The API-only operation stays a WARNING
        assertThat(messages(compilation, Diagnostic.Kind.WARNING)).filteredOn(m -> m.contains(NO_PAGING))
                .singleElement().asString().contains("#export");
    }

    @Test
    void streamAndMapReturnsAreCollectionsToo() {
        Compilation compilation = compileWithOrder("shop.Feed", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.Map;
                import java.util.stream.Stream;
                @AgenticExposed(description = "Feeds")
                public class Feed {
                    @AgenticExposed(description = "Every event")
                    public Stream<String> events() { return Stream.of("a"); }
                    @AgenticExposed(description = "Counts by key")
                    public Map<String, Long> counts() { return Map.of(); }
                    @AgenticExposed(description = "Top counts", maxResults = 3)
                    public Map<String, Long> topCounts() { return Map.of(); }
                }
                """, FLAG_ON);

        assertThat(compilation).succeeded();
        assertThat(messages(compilation, Diagnostic.Kind.WARNING)).filteredOn(m -> m.contains(NO_PAGING))
                .hasSize(2).anyMatch(m -> m.contains("#events returns java.util.stream.Stream<java.lang.String>"))
                .anyMatch(m -> m.contains("#counts returns java.util.Map<java.lang.String,java.lang.Long>"));
    }

    @Test
    void anOptionalOfACollectionIsACollection() {
        String source = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.*;
                import java.util.stream.Stream;
                @AgenticExposed(description = "Maybe")
                public class Maybe {
                    @AgenticExposed(description = "Maybe orders")
                    public Optional<List<Order>> orders() { return Optional.empty(); }
                    @AgenticExposed(description = "Maybe ids")
                    public Optional<long[]> ids() { return Optional.empty(); }
                    @AgenticExposed(description = "Maybe counts")
                    public Optional<Map<String, Long>> counts() { return Optional.empty(); }
                    @AgenticExposed(description = "Maybe events")
                    public Optional<Stream<String>> events() { return Optional.empty(); }
                    @AgenticExposed(description = "Maybe names", maxResults = 2)
                    public Optional<List<String>> names() { return Optional.empty(); }
                    @AgenticExposed(description = "Maybe one")
                    public Optional<Order> one() { return Optional.empty(); }
                }
                """;

        Compilation compilation = compileWithOrder("shop.Maybe", source, FLAG_ON);
        assertThat(compilation).succeeded();
        assertThat(messages(compilation, Diagnostic.Kind.WARNING)).filteredOn(m -> m.contains(NO_PAGING)).hasSize(4)
                .anyMatch(m -> m.startsWith("[ai-atlas] shop.Maybe#orders returns"
                        + " java.util.Optional<java.util.List<shop.Order>> on channels [AI, API]"))
                .anyMatch(m -> m.contains("#ids returns"))
                .anyMatch(m -> m.contains("#counts returns"))
                .anyMatch(m -> m.contains("#events returns"));
        // Under strict, an AI-channel error like any collection
        assertThat(messages(compileWithOrder("shop.Maybe", source, FLAG_ON, STRICT), Diagnostic.Kind.ERROR))
                .filteredOn(m -> m.contains(NO_PAGING)).hasSize(4);
    }

    @Test
    void aBinaryPayloadIsNotACollection() {
        String source = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.Optional;
                @AgenticExposed(description = "Files")
                public class Files {
                    @AgenticExposed(description = "A file")
                    public byte[] file() { return new byte[0]; }
                    @AgenticExposed(description = "Maybe a file")
                    public Optional<byte[]> maybeFile() { return Optional.empty(); }
                    @AgenticExposed(description = "Codes")
                    public short[] codes() { return new short[0]; }
                }
                """;

        Compilation compilation = compileWithOrder("shop.Files", source, FLAG_ON, STRICT);
        assertThat(messages(compilation, Diagnostic.Kind.ERROR)).filteredOn(m -> m.contains(NO_PAGING))
                .singleElement().asString().contains("#codes returns short[]");
        Compilation bounded = compileWithOrder("shop.Files",
                source.replace("\"A file\")", "\"A file\", maxResults = 5)"), FLAG_ON);
        assertThat(bounded).hadErrorContaining("shop.Files#file declares maxResults or a paging role, but returns"
                + " byte[], which is not a collection");
    }

    @Test
    void aBoundOnAStreamIsAnErrorAsItCanNeitherBePublishedNorCounted() {
        Compilation compilation = compileWithOrder("shop.Feed", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.List;
                import java.util.Optional;
                import java.util.stream.Stream;
                import org.springframework.data.domain.Pageable;
                @AgenticExposed(description = "Feeds")
                public class Feed {
                    @AgenticExposed(description = "Top events", maxResults = 3)
                    public Stream<String> topEvents() { return Stream.of("a"); }
                    @AgenticExposed(description = "Maybe top events", maxResults = 3)
                    public Optional<Stream<String>> maybeTopEvents() { return Optional.empty(); }
                    @AgenticExposed(description = "A page of events", maxResults = 3)
                    public Stream<String> pagedEvents(Pageable pageable) { return Stream.of("a"); }
                }
                """, FLAG_ON);

        assertThat(compilation).failed();
        // A Pageable's ceiling bounds the input, which is published and enforced: that stays valid
        assertThat(messages(compilation, Diagnostic.Kind.ERROR)).containsExactlyInAnyOrder(
                "[ai-atlas] shop.Feed#topEvents declares a bound of 3 on a Stream result, which ai-atlas can neither"
                        + " publish, as the stream has no schema, nor check, as counting would consume it. Return a"
                        + " List, or take a Pageable",
                "[ai-atlas] shop.Feed#maybeTopEvents declares a bound of 3 on a Stream result, which ai-atlas can"
                        + " neither publish, as the stream has no schema, nor check, as counting would consume it."
                        + " Return a List, or take a Pageable");
    }

    @Test
    void anExplicitMaxResultsOfMinusOneIsNotTakenForTheDefault() {
        String source = PLAIN_SRC.replace("\"Every order\")", "\"Every order\", maxResults = -1)");
        Compilation compilation = compileWithOrder("shop.Plain", source, FLAG_ON);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("[ai-atlas] shop.Plain#every declares maxResults = -1; a bound"
                + " must be at least 1");
        assertThat(compileWithOrder("shop.Plain", source, FLAG_OFF)).hadErrorContaining(
                "@AgenticExposed(maxResults) on shop.Plain#every requires ai.atlas.collections=true");
        Compilation classLevel = compileWithOrder("shop.Plain",
                PLAIN_SRC.replace("returnType = Order.class)", "returnType = Order.class, maxResults = -1)"), FLAG_ON);
        assertThat(classLevel).hadErrorContaining("@AgenticExposed(maxResults) on shop.Plain must be declared on each"
                + " method, not the class");
    }

    @Test
    void aPageableWithoutACeilingIsAWarningAndAnAiErrorUnderStrictLikeALimitWithoutAMaximum() {
        String source = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import org.springframework.data.domain.*;
                @AgenticExposed(description = "Paged", returnType = Order.class)
                public class Paged {
                    @AgenticExposed(description = "Open")
                    public Page<Order> open(Pageable pageable) { return Page.empty(); }
                    @AgenticExposed(description = "Open for REST", channels = AgenticExposed.Channel.API)
                    public Page<Order> openRest(Pageable pageable) { return Page.empty(); }
                    @AgenticExposed(description = "Capped", maxResults = 50)
                    public Page<Order> capped(Pageable pageable) { return Page.empty(); }
                }
                """;
        String noCeiling = "with no page-size ceiling";

        // Whether or not ai.atlas.constraints is on: the ceiling is maxResults, read either way
        for (String[] options : new String[][] {{FLAG_ON}, {FLAG_ON, CONSTRAINTS}}) {
            Compilation compilation = compileWithOrder("shop.Paged", source, options);
            assertThat(compilation).succeeded();
            assertThat(messages(compilation, Diagnostic.Kind.WARNING)).filteredOn(m -> m.contains(noCeiling))
                    .hasSize(2).contains("[ai-atlas] shop.Paged#open takes a Pageable with no page-size ceiling, so a"
                            + " client can ask for the whole result set in one page. Declare the largest page size"
                            + " with @AgenticExposed(maxResults = N)");
        }
        Compilation strict = compileWithOrder("shop.Paged", source, FLAG_ON, STRICT);
        assertThat(messages(strict, Diagnostic.Kind.ERROR)).filteredOn(m -> m.contains(noCeiling))
                .singleElement().asString().contains("#open ");
        assertThat(messages(strict, Diagnostic.Kind.WARNING)).filteredOn(m -> m.contains(noCeiling))
                .singleElement().asString().contains("#openRest ");
    }

    @Test
    void aLimitWithoutAMaximumIsAWarningWithConstraintsOnAndAnAiErrorUnderStrict() {
        String source = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.List;
                @AgenticExposed(description = "Search", returnType = Order.class)
                public class Search {
                    @AgenticExposed(description = "Find")
                    public List<Order> find(@AgenticParam(paging = Paging.LIMIT) int limit) { return Order.all(); }
                    @AgenticExposed(description = "Find for REST", channels = AgenticExposed.Channel.API)
                    public List<Order> findRest(@AgenticParam(paging = Paging.LIMIT) int limit) { return Order.all(); }
                }
                """;
        String noMax = "which has no maximum";

        assertThat(messages(compileWithOrder("shop.Search", source, FLAG_ON), Diagnostic.Kind.WARNING))
                .noneMatch(m -> m.contains(noMax));
        assertThat(messages(compileWithOrder("shop.Search", source, FLAG_ON, CONSTRAINTS), Diagnostic.Kind.WARNING))
                .filteredOn(m -> m.contains(noMax)).hasSize(2).contains("[ai-atlas] shop.Search#find declares"
                        + " paging = LIMIT on 'limit', which has no maximum, so a client can ask for the whole result"
                        + " set. Declare its ceiling with @Max");
        Compilation strict = compileWithOrder("shop.Search", source, FLAG_ON, CONSTRAINTS, STRICT);
        assertThat(messages(strict, Diagnostic.Kind.ERROR)).filteredOn(m -> m.contains(noMax))
                .singleElement().asString().contains("#find ");
        assertThat(messages(strict, Diagnostic.Kind.WARNING)).filteredOn(m -> m.contains(noMax))
                .singleElement().asString().contains("#findRest ");
    }

    @Test
    void aSizeReturnConstraintIsABoundWithConstraintsOnAndMustAgreeWithMaxResults() {
        String source = """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import jakarta.validation.constraints.Size;
                import java.util.List;
                @AgenticExposed(description = "Sized", returnType = Order.class)
                public class Sized {
                    @Size(max = 10) @AgenticExposed(description = "Sized only")
                    public List<Order> sized() { return Order.all(); }
                    @Size(max = 10) @AgenticExposed(description = "Agreeing", maxResults = 10)
                    public List<Order> agreeing() { return Order.all(); }
                    @Size(max = 10) @AgenticExposed(description = "Disagreeing", maxResults = 5)
                    public List<Order> disagreeing() { return Order.all(); }
                }
                """;

        // Without constraints, @Size on the method is not read: sized is unbounded, the others bounded
        Compilation plain = compileWithOrder("shop.Sized", source, FLAG_ON);
        assertThat(plain).succeeded();
        assertThat(messages(plain, Diagnostic.Kind.WARNING)).filteredOn(m -> m.contains(NO_PAGING))
                .singleElement().asString().contains("#sized ");

        Compilation constrained = compileWithOrder("shop.Sized", source, FLAG_ON, CONSTRAINTS);
        assertThat(constrained).failed();
        assertThat(messages(constrained, Diagnostic.Kind.ERROR)).containsExactly("[ai-atlas] shop.Sized#disagreeing"
                + " declares maxResults = 5 and @Size(max = 10) on its result; they must agree");
        assertThat(messages(constrained, Diagnostic.Kind.WARNING)).noneMatch(m -> m.contains(NO_PAGING));
    }

    // ================================================================ flag on: misuse

    @Test
    void misusedDeclarationsAreErrors() {
        Compilation compilation = compileWithOrder("shop.Bad", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import org.springframework.data.domain.*;
                import java.util.List;
                @AgenticExposed(description = "Bad", returnType = Order.class)
                public class Bad {
                    @AgenticExposed(description = "single", maxResults = 5)
                    public Order single() { return null; }
                    @AgenticExposed(description = "single paged")
                    public Order singleLimit(@AgenticParam(paging = Paging.LIMIT) int n) { return null; }
                    @AgenticExposed(description = "zero", maxResults = 0)
                    public List<Order> zero() { return null; }
                    @AgenticExposed(description = "text limit")
                    public List<Order> textLimit(@AgenticParam(paging = Paging.LIMIT) String n) { return null; }
                    @AgenticExposed(description = "two limits")
                    public List<Order> twoLimits(@AgenticParam(paging = Paging.LIMIT) int a,
                                                 @AgenticParam(paging = Paging.LIMIT) int b) { return null; }
                    @AgenticExposed(description = "two cursors")
                    public List<Order> twoCursors(@AgenticParam(paging = Paging.LIMIT) int n,
                                                  @AgenticParam(paging = Paging.CURSOR) String a,
                                                  @AgenticParam(paging = Paging.CURSOR) String b) { return null; }
                    @AgenticExposed(description = "limit and bound", maxResults = 5)
                    public List<Order> limitAndBound(@AgenticParam(paging = Paging.LIMIT) int n) { return null; }
                    @AgenticExposed(description = "two pageables")
                    public Page<Order> twice(Pageable a, Pageable b) { return null; }
                    @AgenticExposed(description = "pageable and limit")
                    public Page<Order> mixed(Pageable p, @AgenticParam(paging = Paging.LIMIT) int n) { return null; }
                    @AgenticExposed(description = "shadowed")
                    public Page<Order> shadowed(Pageable p, int size) { return null; }
                    @AgenticExposed(description = "sort on text")
                    public List<Order> sortText(@AgenticParam(sortable = "id") String q) { return null; }
                    @AgenticExposed(description = "hidden sort")
                    public Page<Order> hiddenSort(@AgenticParam(sortable = {"id", "creditScore"}) Pageable p) {
                        return null;
                    }
                }
                """, FLAG_ON);

        assertThat(compilation).failed();
        assertThat(messages(compilation, Diagnostic.Kind.ERROR)).containsExactlyInAnyOrder(
                "[ai-atlas] shop.Bad#single declares maxResults or a paging role, but returns shop.Order, which is"
                        + " not a collection",
                "[ai-atlas] shop.Bad#singleLimit declares maxResults or a paging role, but returns shop.Order,"
                        + " which is not a collection",
                "[ai-atlas] shop.Bad#zero declares maxResults = 0; a bound must be at least 1",
                "[ai-atlas] shop.Bad#textLimit declares paging = LIMIT on 'n' of type java.lang.String; a limit"
                        + " must be an int, long or short",
                "[ai-atlas] shop.Bad#twoLimits declares paging = LIMIT on more than one parameter",
                "[ai-atlas] shop.Bad#twoCursors declares paging = CURSOR on more than one parameter",
                "[ai-atlas] shop.Bad#limitAndBound declares a LIMIT parameter and maxResults; the limit bounds each"
                        + " call, so declare its ceiling with @Max on 'n' instead",
                "[ai-atlas] shop.Bad#twice takes more than one Pageable; a paging contract has one",
                "[ai-atlas] shop.Bad#mixed takes a Pageable and declares a LIMIT or CURSOR parameter; a method has"
                        + " one paging contract",
                "[ai-atlas] shop.Bad#shadowed takes a Pageable and a parameter named 'size', which would collide"
                        + " with the Pageable's page, size and sort inputs. Rename the parameter",
                "[ai-atlas] @AgenticParam(sortable) on parameter 'q' of shop.Bad#sortText applies only to a Spring"
                        + " Data Pageable",
                "[ai-atlas] @AgenticParam(sortable) on shop.Bad#hiddenSort names [creditScore], which must each be a"
                        + " distinct field of Order's REST DTO: [id, status, marginCents]. A sort on a property clients"
                        + " cannot see leaks its values through the order");
    }

    @Test
    void sortableIsCheckedAgainstTheRestProjection() {
        // With projections on, an AI-only field is not in the REST DTO, so it cannot be sorted by on REST
        Compilation compilation = com.google.testing.compile.Compiler.javac()
                .withProcessors(new AgenticProcessor())
                .withOptions(FLAG_ON, CollectionsFixtures.PROJECTIONS, CollectionsFixtures.PARAMETERS)
                .compile(JavaFileObjects.forSourceString("shop.Order",
                                CollectionsFixtures.ORDER_SRC.formatted(", channels = Channel.AI")),
                        JavaFileObjects.forSourceString("shop.Sorted", """
                                package shop;
                                import com.egoge.ai.atlas.annotations.*;
                                import org.springframework.data.domain.*;
                                @AgenticExposed(description = "Sorted", returnType = Order.class)
                                public class Sorted {
                                    @AgenticExposed(description = "By margin")
                                    public Page<Order> byMargin(@AgenticParam(sortable = "marginCents") Pageable p) {
                                        return Page.empty();
                                    }
                                }
                                """));

        assertThat(compilation).hadErrorContaining("names [marginCents], which must each be a distinct field of"
                + " Order's REST DTO: [id, status]");
    }
}
