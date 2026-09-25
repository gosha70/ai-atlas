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

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An AI tool without a description of its own is a warning naming the method, the tool and the
 * fallback used; {@code ai.atlas.strict=true} makes it an error; API-only methods are silent
 * (FR-012..FR-014).
 */
class ToolDescriptionDiagnosticTest {

    private static final String MARKER = "has no description of its own";

    @Test
    void classDescriptionFallbackWarns() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order operations")
                public class OrderService {
                    @AgenticExposed(toolName = "listOrders")
                    public String findAll() { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(service);

        assertThat(compilation).succeeded();
        List<Diagnostic<? extends JavaFileObject>> warnings = diagnostics(compilation.warnings());
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage(null))
                .startsWith("[ai-atlas] test.OrderService#findAll (MCP tool 'listOrders')")
                .contains("the class-level description");
        assertThat(warnings.get(0).getSource().getName()).contains("test/OrderService");
    }

    @Test
    void invokesFallbackWarns() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class OrderService {
                    @AgenticExposed
                    public String findAll() { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(service);

        assertThat(compilation).succeeded();
        List<Diagnostic<? extends JavaFileObject>> warnings = diagnostics(compilation.warnings());
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage(null))
                .contains("test.OrderService#findAll (MCP tool 'findAll')")
                .contains("\"Invokes findAll\"");
    }

    @Test
    void methodDescriptionIsSilent() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order operations")
                public class OrderService {
                    @AgenticExposed(description = "Lists every order")
                    public String findAll() { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.strict=true").compile(service);

        assertThat(compilation).succeeded();
        assertThat(diagnostics(compilation.diagnostics())).isEmpty();
    }

    @Test
    void apiOnlyMethodIsSilent() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(channels = { AgenticExposed.Channel.API })
                public class OrderService {
                    public String findAll() { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.strict=true").compile(service);

        assertThat(compilation).succeeded();
        assertThat(diagnostics(compilation.diagnostics())).isEmpty();
    }

    @Test
    void inactiveMethodIsSilent() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class OrderService {
                    @AgenticExposed(apiSince = 2)
                    public String findAll() { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.strict=true").compile(service);

        assertThat(compilation).succeeded();
        assertThat(diagnostics(compilation.diagnostics())).isEmpty();
    }

    @Test
    void strictModeMakesItAnError() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class OrderService {
                    @AgenticExposed
                    public String findAll() { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.strict=TRUE").compile(service);

        assertThat(compilation).failed();
        assertThat(diagnostics(compilation.errors())).singleElement()
                .satisfies(error -> assertThat(error.getMessage(null))
                        .contains("test.OrderService#findAll").contains("\"Invokes findAll\""));
        assertThat(diagnostics(compilation.warnings())).isEmpty();
    }

    @Test
    void strictFalseKeepsItAWarning() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class OrderService {
                    @AgenticExposed
                    public String findAll() { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.strict=False").compile(service);

        assertThat(compilation).succeeded();
        assertThat(diagnostics(compilation.warnings())).hasSize(1);
    }

    @Test
    void badStrictValueIsAnError() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class OrderService {
                    @AgenticExposed(description = "Lists every order")
                    public String findAll() { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.strict=yes").compile(service);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("ai.atlas.strict");
        assertThat(compilation).hadErrorContaining("Got: yes");
    }

    @Test
    void mcpDescriptionsKeepVersionAndDeprecationText() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class OrderService {
                    @AgenticExposed(apiDeprecatedSince = 1, apiReplacement = "findV2")
                    public String findOld() { return null; }
                    @AgenticExposed(apiSince = 2)
                    public String findNew() { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=2").compile(service);

        assertThat(compilation).succeeded();
        assertThat(diagnostics(compilation.warnings())).hasSize(2);
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceMcpTool")
                .contentsAsUtf8String().contains("[DEPRECATED since v1, use findV2] Invokes findOld");
        assertThat(compilation).generatedSourceFile("test.generated.OrderServiceMcpTool")
                .contentsAsUtf8String().contains("[Since v2] Invokes findNew");
    }

    private static List<Diagnostic<? extends JavaFileObject>> diagnostics(
            List<Diagnostic<? extends JavaFileObject>> all) {
        return all.stream().filter(d -> d.getMessage(null).contains(MARKER)).toList();
    }
}
