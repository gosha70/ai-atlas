/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

/**
 * Tests for configurable PII patterns via the {@code -Aai.adam.pii.patterns} option.
 */
class CustomPiiPatternsTest {

    @Test
    void detectsCustomPiiPattern() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Employee",
                """
                package test;
                import ai.adam.annotations.AgentVisible;
                import ai.adam.annotations.AgentVisibleClass;

                @AgentVisibleClass
                public class Employee {
                    @AgentVisible(description = "ID") private Long id;
                    private String salary;
                    private String homeAddress;
                    public Long getId() { return id; }
                    public String getSalary() { return salary; }
                    public String getHomeAddress() { return homeAddress; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.adam.pii.patterns=salary,homeAddress")
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(compilation).hadNoteContaining("Field 'salary' matches PII pattern");
        assertThat(compilation).hadNoteContaining("Field 'homeAddress' matches PII pattern");
    }

    @Test
    void customPatternsAddToDefaults() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.UserProfile",
                """
                package test;
                import ai.adam.annotations.AgentVisible;
                import ai.adam.annotations.AgentVisibleClass;

                @AgentVisibleClass
                public class UserProfile {
                    @AgentVisible(description = "ID") private Long id;
                    private String password;
                    private String phoneNumber;
                    public Long getId() { return id; }
                    public String getPassword() { return password; }
                    public String getPhoneNumber() { return phoneNumber; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.adam.pii.patterns=phoneNumber")
                .compile(source);

        assertThat(compilation).succeeded();
        // Default pattern still works
        assertThat(compilation).hadNoteContaining("Field 'password' matches PII pattern");
        // Custom pattern also works
        assertThat(compilation).hadNoteContaining("Field 'phoneNumber' matches PII pattern");
    }

    @Test
    void defaultPatternsWorkWithoutOption() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Credentials",
                """
                package test;
                import ai.adam.annotations.AgentVisible;
                import ai.adam.annotations.AgentVisibleClass;

                @AgentVisibleClass
                public class Credentials {
                    @AgentVisible(description = "ID") private Long id;
                    private String password;
                    private String ssn;
                    public Long getId() { return id; }
                    public String getPassword() { return password; }
                    public String getSsn() { return ssn; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        assertThat(compilation).succeeded();
        assertThat(compilation).hadNoteContaining("Field 'password' matches PII pattern");
        assertThat(compilation).hadNoteContaining("Field 'ssn' matches PII pattern");
    }
}
