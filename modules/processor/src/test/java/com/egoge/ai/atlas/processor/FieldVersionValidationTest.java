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

class FieldVersionValidationTest {

    private static Compilation compile(JavaFileObject source) {
        return javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);
    }

    @Test
    void sinceVersionZero_emitsError() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID", sinceVersion = 0) private Long id;
                    public Long getId() { return id; }
                }
                """);
        assertThat(compile(source)).hadErrorContaining("sinceVersion must be >= 1");
    }

    @Test
    void sinceVersionNegative_emitsError() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID", sinceVersion = -1) private Long id;
                    public Long getId() { return id; }
                }
                """);
        assertThat(compile(source)).hadErrorContaining("sinceVersion must be >= 1");
    }

    @Test
    void removedInVersionZero_emitsError() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID", removedInVersion = 0) private Long id;
                    public Long getId() { return id; }
                }
                """);
        assertThat(compile(source)).hadErrorContaining("removedInVersion must be >= 1");
    }

    @Test
    void sinceVersionEqualsRemovedInVersion_emitsError() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID", sinceVersion = 2, removedInVersion = 2)
                    private Long id;
                    public Long getId() { return id; }
                }
                """);
        assertThat(compile(source)).hadErrorContaining("sinceVersion (2) must be < removedInVersion (2)");
    }

    @Test
    void sinceVersionGreaterThanRemovedInVersion_emitsError() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID", sinceVersion = 3, removedInVersion = 2)
                    private Long id;
                    public Long getId() { return id; }
                }
                """);
        assertThat(compile(source)).hadErrorContaining("sinceVersion (3) must be < removedInVersion (2)");
    }

    @Test
    void deprecatedSinceVersionNegative_emitsError() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID", deprecatedSinceVersion = -1) private Long id;
                    public Long getId() { return id; }
                }
                """);
        assertThat(compile(source)).hadErrorContaining("deprecatedSinceVersion must be >= 0");
    }

    @Test
    void deprecatedBeforeIntroduction_emitsError() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID", sinceVersion = 3, deprecatedSinceVersion = 2)
                    private Long id;
                    public Long getId() { return id; }
                }
                """);
        assertThat(compile(source)).hadErrorContaining(
                "a field cannot be deprecated before it is introduced");
    }

    @Test
    void deprecatedAfterRemoval_emitsError() {
        // deprecatedSinceVersion >= removedInVersion is invalid
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID", sinceVersion = 1,
                                  removedInVersion = 3, deprecatedSinceVersion = 3)
                    private Long id;
                    public Long getId() { return id; }
                }
                """);
        assertThat(compile(source)).hadErrorContaining(
                "deprecatedSinceVersion (3) must be < removedInVersion (3)");
    }

    @Test
    void deprecatedMessageWithoutDeprecatedSince_emitsWarning() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID", deprecatedMessage = "Use X") private Long id;
                    public Long getId() { return id; }
                }
                """);
        Compilation compilation = compile(source);
        assertThat(compilation).succeeded();
        assertThat(compilation).hadWarningContaining(
                "deprecatedMessage on field 'id' has no effect without deprecatedSinceVersion > 0");
    }
}
