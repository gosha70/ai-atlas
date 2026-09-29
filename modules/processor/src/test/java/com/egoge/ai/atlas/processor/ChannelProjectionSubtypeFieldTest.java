/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.ChannelProjectionFixtures.GeneratedClasses;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.ArrayList;
import java.util.List;

import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.FLAG_ON;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.JSON;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.source;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An {@code @AgenticField} of an unannotated subtype of an {@code @AgenticEntity}, or a collection,
 * iterable or array of one: {@code @AgenticEntity} is not inherited, so without a resolving
 * {@code @AgenticField(type)} hint the field's DTO would copy the raw subtype, every getter of
 * which reaches the MCP result. With {@code ai.atlas.projections=true} that is an ERROR on the
 * field; the hint maps it through the entity's records, projected per channel.
 *
 * <p>The hint's generated classes are loaded and called: the MCP tool through Spring AI's own
 * {@link MethodToolCallbackProvider}, the REST controller serialized with Jackson.
 */
class ChannelProjectionSubtypeFieldTest {

    private static final JavaFileObject PERSON = JavaFileObjects.forSourceString("shop.Person", """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
            @AgenticEntity(description = "A person")
            public class Person {
                @AgenticField(description = "Name") private String name;
                @AgenticField(description = "Secret", channels = Channel.API) private String secret;
                public Person(String name, String secret) { this.name = name; this.secret = secret; }
                public String getName() { return name; }
                public String getSecret() { return secret; }
            }
            """);

    /** {@link #PERSON} without the channel declaration the option off rejects. */
    private static final JavaFileObject PERSON_NO_CHANNELS = JavaFileObjects.forSourceString("shop.Person",
            """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            @AgenticEntity(description = "A person")
            public class Person {
                @AgenticField(description = "Name") private String name;
                @AgenticField(description = "Secret") private String secret;
                public Person(String name, String secret) { this.name = name; this.secret = secret; }
                public String getName() { return name; }
                public String getSecret() { return secret; }
            }
            """);

    /** Not annotated: {@code @AgenticEntity} is not inherited. */
    private static final JavaFileObject VIP = JavaFileObjects.forSourceString("shop.Vip", """
            package shop;
            public class Vip extends Person {
                public Vip(String name, String secret) { super(name, secret); }
                public String getTier() { return "gold"; }
            }
            """);

    // ------------------------------------------------------------ the ERROR

    @Test
    void aSubtypeFieldOfAnAiResponseIsAnErrorRatherThanACopyOfTheRawSubtype() {
        Compilation compilation = compile(envelope("", "Vip", "null"), service(", channels = Channel.AI"));

        assertThat(messages(compilation, Diagnostic.Kind.ERROR)).containsExactly(error("Vip", "the field as"));
        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
    }

    @Test
    void aCollectionOrArrayOfASubtypeIsAnError() {
        String[][] fields = {
                {"java.util.List<Vip>", "java.util.List.of()"},
                {"Iterable<? extends Vip>", "java.util.List.of()"},
                {"Vip[]", "new Vip[0]"},
        };
        for (String[] f : fields) {
            Compilation compilation = compile(envelope("", f[0], f[1]), service(""));

            assertThat(compilation.status()).as(f[0]).isEqualTo(Compilation.Status.FAILURE);
            assertThat(messages(compilation, Diagnostic.Kind.ERROR)).as(f[0])
                    .containsExactly(error("Vip", "its element type as"));
        }
    }

    @Test
    void theErrorIsOnTheField() {
        Compilation compilation = compile(envelope("", "Vip", "null"), service(""));

        List<Diagnostic<? extends JavaFileObject>> errors = compilation.errors().stream()
                .filter(d -> d.getMessage(null).startsWith("[ai-atlas]")).toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getSource().getName()).endsWith("Envelope.java");
        assertThat(errors.get(0).getLineNumber()).isEqualTo(6L);
    }

    @Test
    void theEntityItselfOrAnEntityHintIsValid() {
        String[][] fields = {
                {"", "Person", "null"},
                {"", "java.util.List<Person>", "java.util.List.of()"},
                {", type = Person.class", "Vip", "null"},
                {", type = Person.class", "java.util.List<Vip>", "java.util.List.of()"},
                {", type = Person.class", "Vip[]", "new Vip[0]"},
        };
        for (String[] f : fields) {
            Compilation compilation = compile(envelope(f[0], f[1], f[2]), service(""));

            assertThat(messages(compilation, Diagnostic.Kind.ERROR)).as(f[1]).isEmpty();
            assertThat(messages(compilation, Diagnostic.Kind.WARNING)).as(f[1]).isEmpty();
            assertThat(compilation.status()).as(f[1]).isEqualTo(Compilation.Status.SUCCESS);
        }
    }

    @Test
    void aDirectHintNotAssignableFromTheFieldTypeIsAnError() {
        JavaFileObject other = JavaFileObjects.forSourceString("shop.Other", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity(description = "Another entity")
                public class Other {
                    @AgenticField(description = "Code") private String code;
                    public String getCode() { return code; }
                }
                """);

        Compilation compilation = compile(other, envelope(", type = Other.class", "Vip", "null"), service(""));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(compilation.errors().stream().map(d -> d.getMessage(null)).toList())
                .contains("@AgenticField(type = shop.Other) is not assignable from field type shop.Vip on field"
                        + " 'person' — generated code would pass shop.Vip as shop.Other")
                .contains(error("Vip", "the field as"));
    }

    // ------------------------------------------------------------ the hint remedy, end to end

    @Test
    void theHintMapsTheSubtypeThroughTheEntityRecordsSoTheSecretStaysOffMcp() throws Exception {
        Compilation compilation = compile(envelope(", type = Person.class", "Vip", "new Vip(\"Alice\", \"s3cret\")"),
                service(""));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(source(compilation, "shop.generated.EnvelopeDto"))
                .contains("PersonDto person").contains("PersonDto.fromEntity(entity.getPerson())");
        assertThat(source(compilation, "shop.generated.EnvelopeAiDto"))
                .contains("PersonAiDto person").contains("PersonAiDto.fromEntity(entity.getPerson())");
        GeneratedClasses classes = new GeneratedClasses(compilation);

        JsonNode mcp = classes.mcp("envelope", "{\"id\": 9}");
        assertThat(mcp.path("person").path("name").asText()).isEqualTo("Alice");
        assertThat(mcp.path("person").has("secret")).isFalse();
        assertThat(mcp.path("person").has("tier")).isFalse();
        assertThat(mcp.toString()).doesNotContain("s3cret");

        JsonNode rest = JSON.valueToTree(classes.rest("envelope", 9L));
        assertThat(rest.path("person").path("name").asText()).isEqualTo("Alice");
        assertThat(rest.path("person").path("secret").asText()).isEqualTo("s3cret");
        assertThat(rest.path("person").has("tier")).isFalse();
    }

    // ------------------------------------------------------------ the option off

    @Test
    void withTheOptionOffTheSubtypeFieldIsUnchecked() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .compile(PERSON_NO_CHANNELS, VIP, envelope("", "Vip", "null"), service(""));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(messages(compilation, Diagnostic.Kind.ERROR)).isEmpty();
        assertThat(source(compilation, "shop.generated.EnvelopeDto")).contains("Vip person");
    }

    @Test
    void withTheOptionOffADirectHintStillHasNoEffect() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .compile(PERSON_NO_CHANNELS, VIP, envelope(", type = Person.class", "Vip", "null"), service(""));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(compilation.warnings().stream().map(d -> d.getMessage(null)).toList())
                .contains("@AgenticField(type = ...) on non-collection field 'person' has no effect — hint is only"
                        + " used for collection element types");
        assertThat(source(compilation, "shop.generated.EnvelopeDto")).contains("Vip person")
                .doesNotContain("PersonDto.fromEntity");
    }

    private static String error(String type, String remedy) {
        return "[ai-atlas] Field 'person' of Envelope refers to " + type + ", a subtype of the @AgenticEntity"
                + " Person, so its DTO would copy the raw " + type + ", every getter of it, which"
                + " ai.atlas.projections=true cannot project per channel. Declare " + remedy + " Person, or add"
                + " @AgenticField(type = Person.class)";
    }

    // ------------------------------------------------------------ helpers

    private static JavaFileObject envelope(String hint, String type, String sample) {
        return JavaFileObjects.forSourceString("shop.Envelope", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity(description = "An envelope")
                public class Envelope {
                    @AgenticField(description = "Envelope id") private Long id;
                    @AgenticField(description = "The person"%s) private %s person;
                    public Long getId() { return id; }
                    public %s getPerson() { return person; }
                    public static Envelope sample() {
                        Envelope e = new Envelope();
                        e.id = 9L;
                        e.person = %s;
                        return e;
                    }
                }
                """.formatted(hint, type, type, sample));
    }

    /** Named {@code OrderService}, the service {@link GeneratedClasses} wraps. */
    private static JavaFileObject service(String channels) {
        return JavaFileObjects.forSourceString("shop.OrderService", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
                public class OrderService {
                    @AgenticExposed(description = "An envelope", returnType = Envelope.class%s)
                    public Envelope envelope(Long id) { return Envelope.sample(); }
                }
                """.formatted(channels));
    }

    private static Compilation compile(JavaFileObject... sources) {
        List<JavaFileObject> all = new ArrayList<>(List.of(PERSON, VIP));
        all.addAll(List.of(sources));
        return javac().withProcessors(new AgenticProcessor()).withOptions(FLAG_ON, "-parameters").compile(all);
    }

    private static List<String> messages(Compilation compilation, Diagnostic.Kind kind) {
        return (kind == Diagnostic.Kind.ERROR ? compilation.errors() : compilation.warnings()).stream()
                .map(d -> d.getMessage(null)).filter(m -> m.startsWith("[ai-atlas]")).toList();
    }
}
