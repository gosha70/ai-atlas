/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.List;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code ai.atlas.projections} option and the validation of {@code @AgenticField(channels)}:
 * the option accepts only {@code true} or {@code false}; an empty eligibility, or {@code INHERIT}
 * mixed with explicit channels, is an ERROR on the field; and with the option off, any explicit
 * eligibility is an ERROR naming the field and the option.
 */
class ProjectionsOptionTest {

    private static final String OPTION = "-Aai.atlas.projections=";

    @Test
    void anInvalidOptionValueIsAnError() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions(OPTION + "yes").compile(order(""));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(messages(compilation, Diagnostic.Kind.ERROR))
                .containsExactly("[ai-atlas] ai.atlas.projections must be 'true' or 'false'. Got: yes");
    }

    @Test
    void theOptionAcceptsTrueAndFalseInAnyCase() {
        for (String value : List.of("TRUE", "False")) {
            Compilation compilation = javac().withProcessors(new AgenticProcessor())
                    .withOptions(OPTION + value).compile(order(""));

            assertThat(compilation.status()).as(value).isEqualTo(Compilation.Status.SUCCESS);
        }
    }

    @Test
    void anExplicitEligibilityWithTheOptionOffIsAnErrorNamingTheFieldAndTheOption() {
        for (String options : List.of("", OPTION + "false")) {
            Compilation compilation = (options.isEmpty() ? javac() : javac().withOptions(options))
                    .withProcessors(new AgenticProcessor())
                    .compile(order("channels = AgenticExposed.Channel.API"));

            assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
            List<Diagnostic<? extends JavaFileObject>> errors = compilation.errors();
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).getMessage(null)).isEqualTo("[ai-atlas] @AgenticField(channels) on field"
                    + " 'margin' of Order requires ai.atlas.projections=true. Without it the field is served on"
                    + " every channel. Turn the option on (agentic { projections = true } in Gradle) or remove"
                    + " the declaration");
            assertThat(errors.get(0).getLineNumber()).isEqualTo(8L);
        }
    }

    @Test
    void explicitlyDeclaringEveryChannelIsStillADeclarationWithTheOptionOff() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .compile(order("channels = { AgenticExposed.Channel.AI, AgenticExposed.Channel.API }"));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(messages(compilation, Diagnostic.Kind.ERROR)).singleElement().asString()
                .contains("'margin' of Order requires ai.atlas.projections=true");
    }

    @Test
    void anEmptyEligibilityIsAnError() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions(OPTION + "true").compile(order("channels = {}"));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(messages(compilation, Diagnostic.Kind.ERROR)).containsExactly("[ai-atlas] @AgenticField(channels)"
                + " on field 'margin' of Order must not be empty. Omit it to keep the field on every channel");
    }

    @Test
    void inheritMixedWithAnExplicitChannelIsAnError() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).withOptions(OPTION + "true")
                .compile(order("channels = { AgenticExposed.Channel.INHERIT, AgenticExposed.Channel.AI }"));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(messages(compilation, Diagnostic.Kind.ERROR)).containsExactly("[ai-atlas] @AgenticField(channels)"
                + " on field 'margin' of Order must not mix INHERIT with explicit values");
    }

    @Test
    void theDefaultEligibilityIsNoDeclarationWithTheOptionOff() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .compile(order("channels = AgenticExposed.Channel.INHERIT"));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
    }

    /** An entity whose {@code margin} field carries {@code eligibility} (line 8). */
    static JavaFileObject order(String eligibility) {
        return JavaFileObjects.forSourceString("test.Order", """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticEntity;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                import com.egoge.ai.atlas.annotations.AgenticField;
                @AgenticEntity(description = "An order")
                public class Order {
                    @AgenticField(description = "Id") private Long id;
                    @AgenticField(description = "Margin"%s) private Long margin;
                    public Long getId() { return id; }
                    public Long getMargin() { return margin; }
                }
                """.formatted(eligibility.isEmpty() ? "" : ", " + eligibility));
    }

    private static List<String> messages(Compilation compilation, Diagnostic.Kind kind) {
        return compilation.diagnostics().stream().filter(d -> d.getKind() == kind)
                .map(d -> d.getMessage(null)).toList();
    }
}
