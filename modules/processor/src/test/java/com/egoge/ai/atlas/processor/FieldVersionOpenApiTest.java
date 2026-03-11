/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

class FieldVersionOpenApiTest {

    private static Compilation compile(int apiMajor, JavaFileObject source) {
        return javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=" + apiMajor)
                .compile(source);
    }

    private static String getOpenApiJson(Compilation compilation) {
        var file = compilation.generatedFile(
                StandardLocation.CLASS_OUTPUT, "META-INF/openapi/openapi.json");
        assertThat(file).as("OpenAPI spec").isPresent();
        try {
            return file.get().getCharContent(true).toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void deprecatedField_hasDeprecatedFlag() {
        // deprecatedSinceVersion=1, major=2 → deprecated=true in schema
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID") private Long id;
                    @AgenticField(description = "Old name", deprecatedSinceVersion = 1)
                    private String customerName;
                    public Long getId() { return id; }
                    public String getCustomerName() { return customerName; }
                }
                """);

        Compilation compilation = compile(2, source);
        assertThat(compilation).succeeded();
        String json = getOpenApiJson(compilation);
        // The customerName property should have deprecated: true
        assertThat(json).contains("\"deprecated\" : true");
    }

    @Test
    void deprecatedFieldWithMessage_hasEnrichedDescription() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID") private Long id;
                    @AgenticField(description = "Old name", deprecatedSinceVersion = 2,
                                  deprecatedMessage = "Use X")
                    private String customerName;
                    public Long getId() { return id; }
                    public String getCustomerName() { return customerName; }
                }
                """);

        Compilation compilation = compile(2, source);
        assertThat(compilation).succeeded();
        String json = getOpenApiJson(compilation);
        assertThat(json).contains("[DEPRECATED since v2: Use X]");
    }

    @Test
    void deprecatedFieldNotYetDeprecated_noFlag() {
        // deprecatedSinceVersion=3, major=2 → NOT deprecated yet
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID") private Long id;
                    @AgenticField(description = "Name", deprecatedSinceVersion = 3)
                    private String customerName;
                    public Long getId() { return id; }
                    public String getCustomerName() { return customerName; }
                }
                """);

        Compilation compilation = compile(2, source);
        assertThat(compilation).succeeded();
        String json = getOpenApiJson(compilation);
        assertThat(json).doesNotContain("\"deprecated\" : true");
        assertThat(json).doesNotContain("[DEPRECATED");
    }

    @Test
    void removedField_absentFromSchema() {
        // removedInVersion=2, major=2 → field excluded (exclusive semantics)
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID") private Long id;
                    @AgenticField(description = "Old", removedInVersion = 2) private String oldField;
                    public Long getId() { return id; }
                    public String getOldField() { return oldField; }
                }
                """);

        Compilation compilation = compile(2, source);
        assertThat(compilation).succeeded();
        String json = getOpenApiJson(compilation);
        assertThat(json).doesNotContain("oldField");
    }

    @Test
    void futureField_absentFromSchema() {
        // sinceVersion=3, major=2 → field not yet introduced
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID") private Long id;
                    @AgenticField(description = "Future", sinceVersion = 3) private String futureField;
                    public Long getId() { return id; }
                    public String getFutureField() { return futureField; }
                }
                """);

        Compilation compilation = compile(2, source);
        assertThat(compilation).succeeded();
        String json = getOpenApiJson(compilation);
        assertThat(json).doesNotContain("futureField");
    }
}
