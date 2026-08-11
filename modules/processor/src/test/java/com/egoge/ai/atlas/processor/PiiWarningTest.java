/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

class PiiWarningTest {

    @Test
    void emitsNoteForSsnField() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Person",
                """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;

                @AgenticEntity
                public class Person {
                    @AgenticField(description = "Person ID")
                    private Long id;

                    private String ssn;

                    public Long getId() { return id; }
                    public String getSsn() { return ssn; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(compilation).hadNoteContaining("Field 'ssn' matches PII pattern");
    }

    @Test
    void emitsNoteForPasswordField() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Account",
                """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;

                @AgenticEntity
                public class Account {
                    @AgenticField(description = "Account ID")
                    private Long id;

                    private String password;

                    public Long getId() { return id; }
                    public String getPassword() { return password; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(compilation).hadNoteContaining("Field 'password' matches PII pattern");
    }

    @Test
    void emitsNoteForCreditCardField() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Payment",
                """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;

                @AgenticEntity
                public class Payment {
                    @AgenticField(description = "Payment ID")
                    private Long id;

                    private String creditCardNumber;

                    public Long getId() { return id; }
                    public String getCreditCardNumber() { return creditCardNumber; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(compilation).hadNoteContaining("Field 'creditCardNumber' matches PII pattern");
    }

    @Test
    void noWarningForNonPiiFields() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Simple",
                """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;

                @AgenticEntity
                public class Simple {
                    @AgenticField(description = "ID")
                    private Long id;

                    private String description;

                    public Long getId() { return id; }
                    public String getDescription() { return description; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(compilation).hadNoteContaining("Generated DTO");
        assertThat(compilation).hadNoteContaining("Generated OpenAPI spec");
        // Should not contain PII warnings — only generation notes (DTO + OpenAPI versioned + OpenAPI alias + api-version.properties + deprecation-manifest.json)
        assertThat(compilation).hadNoteCount(5);
    }
}
