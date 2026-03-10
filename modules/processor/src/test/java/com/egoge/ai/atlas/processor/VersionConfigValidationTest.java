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

class VersionConfigValidationTest {

    private static final JavaFileObject ENTITY = JavaFileObjects.forSourceString("test.Order",
            """
            package test;
            import com.egoge.ai.atlas.annotations.AgenticField;
            import com.egoge.ai.atlas.annotations.AgenticEntity;
            @AgenticEntity
            public class Order {
                @AgenticField(description = "ID") private Long id;
                public Long getId() { return id; }
            }
            """);

    private static final JavaFileObject SERVICE = JavaFileObjects.forSourceString("test.OrderService",
            """
            package test;
            import com.egoge.ai.atlas.annotations.AgenticExposed;
            @AgenticExposed(description = "Order ops", returnType = Order.class)
            public class OrderService {
                public Order findById(Long id) { return null; }
            }
            """);

    @Test
    void invalidBasePath_noLeadingSlash_emitsError() {
        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.basePath=api")
                .compile(ENTITY, SERVICE);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("must start with '/'");
    }

    @Test
    void invalidMajor_notAnInteger_emitsError() {
        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=abc")
                .compile(ENTITY, SERVICE);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("must be an integer");
    }

    @Test
    void invalidMajor_negative_emitsError() {
        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=-1")
                .compile(ENTITY, SERVICE);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("must be a positive integer");
    }

    @Test
    void invalidBasePath_rootSlash_emitsError() {
        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.basePath=/")
                .compile(ENTITY, SERVICE);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("must not be '/'");
    }

    @Test
    void invalidBasePath_trailingSlashNormalizesToRoot_emitsError() {
        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.basePath=///")
                .compile(ENTITY, SERVICE);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("must not be '/'");
    }

    @Test
    void blankInfoVersion_emitsError() {
        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.openapi.infoVersion= ")
                .compile(ENTITY, SERVICE);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("must not be empty");
    }
}
