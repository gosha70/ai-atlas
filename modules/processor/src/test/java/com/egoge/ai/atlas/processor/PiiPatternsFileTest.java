/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

/**
 * Tests for file-based PII pattern customization via
 * {@code -Aai.atlas.pii.patterns.file=path}.
 */
class PiiPatternsFileTest {

    @TempDir
    Path tempDir;

    @Test
    void customFileReplacesDefaults() throws IOException {
        Path patternsFile = tempDir.resolve("custom-pii.conf");
        Files.writeString(patternsFile, "salary\n");

        JavaFileObject source = JavaFileObjects.forSourceString("test.Account",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;

                @AgenticEntity
                public class Account {
                    @AgenticField(description = "ID") private Long id;
                    private String password;
                    private String salary;
                    public Long getId() { return id; }
                    public String getPassword() { return password; }
                    public String getSalary() { return salary; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.pii.patterns.file=" + patternsFile.toAbsolutePath())
                .compile(source);

        assertThat(compilation).succeeded();
        // Custom file only has "salary" — password (a default) should NOT match
        assertThat(compilation).hadNoteContaining("Field 'salary' matches PII pattern");
        // Verify no PII warning for password (it's a default, but custom file replaces defaults)
        assertThat(compilation).hadNoteCount(6); // salary PII + Generated DTO + OpenAPI versioned + OpenAPI alias + api-version.properties + deprecation-manifest.json
    }

    @Test
    void customFilePlusAdditiveOption() throws IOException {
        Path patternsFile = tempDir.resolve("custom-pii.conf");
        Files.writeString(patternsFile, "salary\n");

        JavaFileObject source = JavaFileObjects.forSourceString("test.Compensation",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;

                @AgenticEntity
                public class Compensation {
                    @AgenticField(description = "ID") private Long id;
                    private String salary;
                    private String bonus;
                    public Long getId() { return id; }
                    public String getSalary() { return salary; }
                    public String getBonus() { return bonus; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions(
                        "-Aai.atlas.pii.patterns.file=" + patternsFile.toAbsolutePath(),
                        "-Aai.atlas.pii.patterns=bonus")
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(compilation).hadNoteContaining("Field 'salary' matches PII pattern");
        assertThat(compilation).hadNoteContaining("Field 'bonus' matches PII pattern");
    }

    @Test
    void missingFileEmitsWarningAndFallsBackToDefaults() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Fallback",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;

                @AgenticEntity
                public class Fallback {
                    @AgenticField(description = "ID") private Long id;
                    private String password;
                    public Long getId() { return id; }
                    public String getPassword() { return password; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.pii.patterns.file=/nonexistent/path/pii.conf")
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(compilation).hadWarningContaining("Could not read PII patterns file");
        assertThat(compilation).hadNoteContaining("Field 'password' matches PII pattern");
    }

    @Test
    void commentsAndBlankLinesAreIgnored() throws IOException {
        Path patternsFile = tempDir.resolve("commented-pii.conf");
        Files.writeString(patternsFile, """
                # This is a comment
                salary

                # Another comment
                bonus
                """);

        JavaFileObject source = JavaFileObjects.forSourceString("test.Pay",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;

                @AgenticEntity
                public class Pay {
                    @AgenticField(description = "ID") private Long id;
                    private String salary;
                    private String bonus;
                    public Long getId() { return id; }
                    public String getSalary() { return salary; }
                    public String getBonus() { return bonus; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.pii.patterns.file=" + patternsFile.toAbsolutePath())
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(compilation).hadNoteContaining("Field 'salary' matches PII pattern");
        assertThat(compilation).hadNoteContaining("Field 'bonus' matches PII pattern");
    }

    @Test
    void defaultResourceUsedWhenNoFileOption() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.DefaultCheck",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;

                @AgenticEntity
                public class DefaultCheck {
                    @AgenticField(description = "ID") private Long id;
                    private String ssn;
                    private String creditCardNumber;
                    public Long getId() { return id; }
                    public String getSsn() { return ssn; }
                    public String getCreditCardNumber() { return creditCardNumber; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(compilation).hadNoteContaining("Field 'ssn' matches PII pattern");
        assertThat(compilation).hadNoteContaining("Field 'creditCardNumber' matches PII pattern");
    }
}
