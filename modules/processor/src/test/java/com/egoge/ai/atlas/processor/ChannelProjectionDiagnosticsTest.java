/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.ChannelProjectionFixtures.LaterRoundProcessor;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.FLAG_ON;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.shop;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.source;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The compile-time diagnostics of {@code ai.atlas.projections=true}: an empty intersection of an
 * operation's channel with its returned entity's fields, and a channel-eligible reference to an
 * entity with no field for that channel, are ERRORs; so is an entity-returning method without a
 * resolvable {@code returnType}, and an AI record name colliding with another type. An AI-eligible
 * field whose name looks like PII is a WARNING, never an ERROR.
 */
class ChannelProjectionDiagnosticsTest {

    /** An entity whose only field is API-only. */
    private static final JavaFileObject KEY = JavaFileObjects.forSourceString("shop.Key", """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
            @AgenticEntity(description = "An access key")
            public class Key {
                @AgenticField(description = "Key value", channels = Channel.API) private String value;
                public String getValue() { return value; }
            }
            """);

    // ------------------------------------------------------------ empty intersections

    @Test
    void anOperationWhoseChannelLeavesItsEntityNoFieldIsAnErrorOnTheMethod() {
        JavaFileObject vault = JavaFileObjects.forSourceString("shop.Vault", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                public class Vault {
                    @AgenticExposed(description = "Read a key", returnType = Key.class)
                    public Key read(String name) { return null; }
                }
                """);

        Compilation compilation = compile(KEY, vault);

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        List<Diagnostic<? extends JavaFileObject>> errors = diagnostics(compilation, Diagnostic.Kind.ERROR);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getMessage(null)).isEqualTo("[ai-atlas] Method 'read' is exposed on the AI channel,"
                + " but the entity it returns, Key, has no field eligible for it. Leave AI out of the method's"
                + " @AgenticExposed(channels), or make a field of Key eligible for AI");
        assertThat(errors.get(0).getSource().getName()).endsWith("shop/Vault.java");
        assertThat(errors.get(0).getLineNumber()).isEqualTo(5L);
    }

    @Test
    void narrowingTheOperationToAChannelWithFieldsResolvesIt() {
        JavaFileObject vault = JavaFileObjects.forSourceString("shop.Vault", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
                public class Vault {
                    @AgenticExposed(description = "Read a key", returnType = Key.class, channels = Channel.API)
                    public Key read(String name) { return null; }
                }
                """);

        Compilation compilation = compile(KEY, vault);

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(compilation.generatedSourceFile("shop.generated.KeyAiDto")).isEmpty();
        assertThat(compilation.generatedSourceFile("shop.generated.KeyDto")).isPresent();
    }

    @Test
    void aChannelEligibleReferenceToAnEntityWithNoFieldForTheChannelIsAnErrorOnTheField() {
        JavaFileObject account = JavaFileObjects.forSourceString("shop.Account", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.List;
                @AgenticEntity(description = "An account")
                public class Account {
                    @AgenticField(description = "Id") private Long id;
                    @AgenticField(description = "Its keys") private List<Key> keys;
                    public Long getId() { return id; }
                    public List<Key> getKeys() { return keys; }
                }
                """);

        Compilation compilation = compile(KEY, account);

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        List<Diagnostic<? extends JavaFileObject>> errors = diagnostics(compilation, Diagnostic.Kind.ERROR);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getMessage(null)).isEqualTo("[ai-atlas] Field 'keys' of Account is eligible for the"
                + " AI channel, but the entity it refers to, Key, has no field eligible for it. Leave AI out of"
                + " the field's @AgenticField(channels), or make a field of Key eligible for AI");
        assertThat(errors.get(0).getSource().getName()).endsWith("shop/Account.java");
        assertThat(errors.get(0).getLineNumber()).isEqualTo(7L);
    }

    @Test
    void aReferenceOnlyOnAChannelTheEntityHasFieldsForIsValid() {
        JavaFileObject account = JavaFileObjects.forSourceString("shop.Account", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
                import java.util.List;
                @AgenticEntity(description = "An account")
                public class Account {
                    @AgenticField(description = "Id") private Long id;
                    @AgenticField(description = "Its keys", channels = Channel.API) private List<Key> keys;
                    public Long getId() { return id; }
                    public List<Key> getKeys() { return keys; }
                }
                """);

        Compilation compilation = compile(KEY, account);

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(source(compilation, "shop.generated.AccountAiDto"))
                .contains("Long id").doesNotContain("keys");
    }

    // ------------------------------------------------------------ the raw-entity path

    @Test
    void anEntityReturnWithoutAResolvableReturnTypeIsAnError() {
        String[][] returns = {
                {"Key", "null"},
                {"java.util.List<Key>", "java.util.List.of()"},
                {"Iterable<? extends Key>", "java.util.List.of()"},
                {"Key[]", "new Key[0]"},
                {"java.util.ArrayList<Key>", "new java.util.ArrayList<>()"},
        };
        for (String[] r : returns) {
            JavaFileObject service = JavaFileObjects.forSourceString("shop.KeyService", """
                    package shop;
                    import com.egoge.ai.atlas.annotations.*;
                    import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
                    public class KeyService {
                        @AgenticExposed(description = "Keys", channels = Channel.API)
                        public %s keys() { return %s; }
                    }
                    """.formatted(r[0], r[1]));

            Compilation compilation = compile(KEY, service);

            assertThat(compilation.status()).as(r[0]).isEqualTo(Compilation.Status.FAILURE);
            assertThat(messages(compilation, Diagnostic.Kind.ERROR)).as(r[0]).containsExactly("[ai-atlas] Method"
                    + " 'keys' returns the @AgenticEntity Key without a resolvable @AgenticExposed(returnType), so its"
                    + " REST and MCP wrappers would return the raw entity, which ai.atlas.projections=true cannot"
                    + " project per channel. Declare returnType = Key.class");
        }
    }

    @Test
    void theRawEntityReturnIsAllowedWithTheOptionOffAndForAnInactiveOperation() {
        JavaFileObject service = JavaFileObjects.forSourceString("shop.NoteService", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                public class NoteService {
                    @AgenticExposed(description = "A note")
                    public Note note() { return null; }
                    @AgenticExposed(description = "A retired note", apiSince = 2)
                    public Note retired() { return null; }
                }
                """);
        JavaFileObject note = JavaFileObjects.forSourceString("shop.Note", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity(description = "A note")
                public class Note {
                    @AgenticField(description = "Text") private String text;
                    public String getText() { return text; }
                }
                """);

        assertThat(javac().withProcessors(new AgenticProcessor()).compile(note, service).status())
                .isEqualTo(Compilation.Status.SUCCESS);
        Compilation on = compile(note, service);
        assertThat(messages(on, Diagnostic.Kind.ERROR)).singleElement().asString().contains("Method 'note' returns");
    }

    @Test
    void aNonEntityReturnWithoutAReturnTypeIsValid() {
        JavaFileObject service = JavaFileObjects.forSourceString("shop.KeyService", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.List;
                public class KeyService {
                    @AgenticExposed(description = "Key names")
                    public List<String> names() { return List.of(); }
                    @AgenticExposed(description = "Rotate")
                    public void rotate() { }
                }
                """);

        assertThat(compile(KEY, service).status()).isEqualTo(Compilation.Status.SUCCESS);
    }

    // ------------------------------------------------------------ names

    @Test
    void anAiRecordNameCollidingWithADeclaredTypeIsAnError() {
        JavaFileObject declared = JavaFileObjects.forSourceString("shop.generated.KeyAiDto", """
                package shop.generated;
                public class KeyAiDto {
                }
                """);
        JavaFileObject key = JavaFileObjects.forSourceString("shop.Key", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
                @AgenticEntity(description = "An access key")
                public class Key {
                    @AgenticField(description = "Id") private Long id;
                    @AgenticField(description = "Key value", channels = Channel.API) private String value;
                    public Long getId() { return id; }
                    public String getValue() { return value; }
                }
                """);

        Compilation compilation = compile(key, declared);

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(messages(compilation, Diagnostic.Kind.ERROR)).containsExactly("[ai-atlas] The AI record"
                + " shop.generated.KeyAiDto of Key collides with a type the compilation declares. Name it with"
                + " @AgenticEntity(aiDtoName)");
    }

    @Test
    void anAiRecordNameCollidingWithAnotherEntitysRecordIsAnError() {
        List<JavaFileObject> sources = new ArrayList<>(shop(true));
        sources.add(JavaFileObjects.forSourceString("shop.Summary", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity(description = "A summary", dtoName = "OrderAiDto")
                public class Summary {
                    @AgenticField(description = "Text") private String text;
                    public String getText() { return text; }
                }
                """));
        sources.add(JavaFileObjects.forSourceString("shop.Receipt", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
                @AgenticEntity(description = "A receipt", aiDtoName = "ShipmentAiDto")
                public class Receipt {
                    @AgenticField(description = "Total") private Long total;
                    @AgenticField(description = "Clerk", channels = Channel.API) private String clerk;
                    public Long getTotal() { return total; }
                    public String getClerk() { return clerk; }
                }
                """));

        Compilation compilation = compile(sources.toArray(JavaFileObject[]::new));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(messages(compilation, Diagnostic.Kind.ERROR))
                .contains("[ai-atlas] The AI record shop.generated.OrderAiDto of Order collides with the DTO of"
                        + " Summary. Name it with @AgenticEntity(aiDtoName)")
                .contains("[ai-atlas] The AI record shop.generated.ShipmentAiDto of Receipt collides with the AI"
                        + " record of Shipment. Name it with @AgenticEntity(aiDtoName)");
    }

    @Test
    void aLaterRoundDtoNamedLikeAnEarlierAiRecordIsAnError() {
        LaterRoundProcessor summaries = new LaterRoundProcessor(Map.of("shop.late.Summary", """
                package shop.late;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity(description = "A summary", packageName = "shop.generated", dtoName = "OrderAiDto")
                public class Summary {
                    @AgenticField(description = "Text") private String text;
                    public String getText() { return text; }
                }
                """));

        Compilation compilation = javac().withProcessors(new AgenticProcessor(), summaries).withOptions(FLAG_ON)
                .compile(shop(true));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(messages(compilation, Diagnostic.Kind.ERROR)).containsExactly("[ai-atlas] The DTO"
                + " shop.generated.OrderAiDto of Summary collides with the AI record of Order. Name it with"
                + " @AgenticEntity(dtoName)");
    }

    @Test
    void anAiDtoNameThatIsNotAnIdentifierIsAnError() {
        JavaFileObject key = JavaFileObjects.forSourceString("shop.Key", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity(description = "An access key", aiDtoName = "Key-For-Agents")
                public class Key {
                    @AgenticField(description = "Id") private Long id;
                    public Long getId() { return id; }
                }
                """);

        Compilation compilation = compile(key);

        assertThat(messages(compilation, Diagnostic.Kind.ERROR)).containsExactly(
                "[ai-atlas] @AgenticEntity(aiDtoName) on Key must be a Java identifier. Got: Key-For-Agents");
    }

    @Test
    void anAiDtoNameWithTheOptionOffIsAWarning() {
        JavaFileObject key = JavaFileObjects.forSourceString("shop.Key", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity(description = "An access key", aiDtoName = "KeyForAgents")
                public class Key {
                    @AgenticField(description = "Id") private Long id;
                    public Long getId() { return id; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(key);

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(messages(compilation, Diagnostic.Kind.WARNING)).containsExactly(
                "[ai-atlas] @AgenticEntity(aiDtoName) on Key has no effect without ai.atlas.projections=true");
    }

    // ------------------------------------------------------------ PII

    @Test
    void anAiEligibleFieldNamedLikePiiIsAWarningEvenUnderStrict() {
        JavaFileObject person = person("");
        for (String strict : List.of("false", "true")) {
            Compilation compilation = javac().withProcessors(new AgenticProcessor())
                    .withOptions(FLAG_ON, "-Aai.atlas.strict=" + strict).compile(person);

            assertThat(compilation.status()).as(strict).isEqualTo(Compilation.Status.SUCCESS);
            List<Diagnostic<? extends JavaFileObject>> warnings = diagnostics(compilation, Diagnostic.Kind.WARNING);
            assertThat(warnings).as(strict).hasSize(1);
            assertThat(warnings.get(0).getMessage(null)).isEqualTo("[ai-atlas] Field 'passportNumber' of Person"
                    + " matches a PII pattern and is eligible for the AI channel, so MCP tool results carry it into"
                    + " agents' context. If agents must not see it, declare @AgenticField(channels = API)");
            assertThat(warnings.get(0).getLineNumber()).isEqualTo(7L);
        }
    }

    @Test
    void anApiOnlyPiiFieldAndTheOptionOffDrawNoWarning() {
        Compilation apiOnly = compile(person(", channels = Channel.API"));
        Compilation off = javac().withProcessors(new AgenticProcessor()).compile(person(""));

        assertThat(diagnostics(apiOnly, Diagnostic.Kind.WARNING)).isEmpty();
        assertThat(diagnostics(off, Diagnostic.Kind.WARNING)).isEmpty();
    }

    private static JavaFileObject person(String eligibility) {
        return JavaFileObjects.forSourceString("shop.Person", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
                @AgenticEntity(description = "A person")
                public class Person {
                    @AgenticField(description = "Name") private String name;
                    @AgenticField(description = "Passport"%s) private String passportNumber;
                    public String getName() { return name; }
                    public String getPassportNumber() { return passportNumber; }
                }
                """.formatted(eligibility));
    }

    // ------------------------------------------------------------ helpers

    private static Compilation compile(JavaFileObject... sources) {
        return javac().withProcessors(new AgenticProcessor()).withOptions(FLAG_ON).compile(sources);
    }

    /** The ai-atlas ERRORs or WARNINGs of a compilation. */
    private static List<Diagnostic<? extends JavaFileObject>> diagnostics(Compilation compilation,
                                                                         Diagnostic.Kind kind) {
        return (kind == Diagnostic.Kind.ERROR ? compilation.errors() : compilation.warnings()).stream()
                .filter(d -> d.getMessage(null).startsWith("[ai-atlas]")).toList();
    }

    private static List<String> messages(Compilation compilation, Diagnostic.Kind kind) {
        return diagnostics(compilation, kind).stream().map(d -> d.getMessage(null)).toList();
    }
}
