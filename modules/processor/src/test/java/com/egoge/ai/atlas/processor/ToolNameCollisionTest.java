/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.util.List;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two AI tools can never share an effective MCP tool name: collisions across services and between
 * overloads are compile errors at every site, and an explicit {@code toolName} resolves them
 * (FR-009..FR-011).
 */
class ToolNameCollisionTest {

    private static final String COLLISION_MARKER = "MCP tool name";

    private static final JavaFileObject ORDER_SERVICE = JavaFileObjects.forSourceString("test.OrderService",
            """
            package test;
            import com.egoge.ai.atlas.annotations.AgenticExposed;
            public class OrderService {
                @AgenticExposed(description = "Order by id")
                public String findById(Long id) { return null; }
            }
            """);

    @Test
    void sameMethodNameOnTwoServicesIsACompileErrorAtEachSite() {
        JavaFileObject customers = JavaFileObjects.forSourceString("other.CustomerService",
                """
                package other;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class CustomerService {
                    @AgenticExposed(description = "Customer by id")
                    public String findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(ORDER_SERVICE, customers);

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        List<Diagnostic<? extends JavaFileObject>> errors = collisionErrors(compilation);
        assertThat(errors).hasSize(2);
        assertThat(errors).extracting(error -> error.getSource().getName())
                .anySatisfy(name -> assertThat(name).contains("test/OrderService"))
                .anySatisfy(name -> assertThat(name).contains("other/CustomerService"));
        assertThat(errors).allSatisfy(error -> assertThat(error.getMessage(null))
                .contains("'findById'").contains("toolName"));
        assertThat(errors).anySatisfy(error -> assertThat(error.getMessage(null))
                .contains("of test.OrderService#findById is also used by other.CustomerService#findById"));
        assertThat(errors).anySatisfy(error -> assertThat(error.getMessage(null))
                .contains("of other.CustomerService#findById is also used by test.OrderService#findById"));
    }

    @Test
    void overloadsOfOneMethodCollide() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Orders", channels = { AgenticExposed.Channel.AI })
                public class OrderService {
                    public String find(Long id) { return null; }
                    public String find(String code) { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(service);

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        List<Diagnostic<? extends JavaFileObject>> errors = collisionErrors(compilation);
        assertThat(errors).extracting(Diagnostic::getLineNumber).containsExactlyInAnyOrder(5L, 6L);
        assertThat(errors).allSatisfy(error -> assertThat(error.getMessage(null))
                .contains("'find' of test.OrderService#find is also used by test.OrderService#find"));
    }

    @Test
    void threeWayCollisionNamesBothOtherDeclarations() {
        JavaFileObject a = service("a", "Order");
        JavaFileObject b = service("b", "Customer");
        JavaFileObject c = service("c", "Invoice");

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(a, b, c);

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        List<Diagnostic<? extends JavaFileObject>> errors = collisionErrors(compilation);
        assertThat(errors).hasSize(3);
        assertThat(errors).anySatisfy(error -> assertThat(error.getMessage(null))
                .contains("of a.OrderService#lookup is also used by b.CustomerService#lookup, c.InvoiceService#lookup"));
        assertThat(errors).anySatisfy(error -> assertThat(error.getMessage(null))
                .contains("of b.CustomerService#lookup is also used by a.OrderService#lookup, c.InvoiceService#lookup"));
        assertThat(errors).anySatisfy(error -> assertThat(error.getMessage(null))
                .contains("of c.InvoiceService#lookup is also used by a.OrderService#lookup, b.CustomerService#lookup"));
    }

    @Test
    void explicitToolNameResolvesTheCollision() throws IOException {
        JavaFileObject customers = JavaFileObjects.forSourceString("other.CustomerService",
                """
                package other;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class CustomerService {
                    @AgenticExposed(description = "Customer by id", toolName = "findCustomerById")
                    public String findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(ORDER_SERVICE, customers);

        assertThat(compilation.status()).as(compilation.diagnostics().toString())
                .isEqualTo(Compilation.Status.SUCCESS);
        assertThat(generatedSource(compilation, "other.generated.CustomerServiceMcpTool"))
                .contains("name = \"findCustomerById\"");
        assertThat(generatedSource(compilation, "test.generated.OrderServiceMcpTool"))
                .contains("name = \"findById\"");
    }

    @Test
    void apiOnlyMethodsDoNotTakePart() {
        JavaFileObject customers = JavaFileObjects.forSourceString("other.CustomerService",
                """
                package other;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class CustomerService {
                    @AgenticExposed(description = "Customer by id", channels = { AgenticExposed.Channel.API })
                    public String findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(ORDER_SERVICE, customers);

        assertThat(compilation.status()).as(compilation.diagnostics().toString())
                .isEqualTo(Compilation.Status.SUCCESS);
        assertThat(collisionErrors(compilation)).isEmpty();
    }

    @Test
    void methodsInactiveAtTheConfiguredMajorDoNotTakePart() {
        JavaFileObject customers = JavaFileObjects.forSourceString("other.CustomerService",
                """
                package other;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class CustomerService {
                    @AgenticExposed(description = "Customer by id", apiSince = 2)
                    public String findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(ORDER_SERVICE, customers);

        assertThat(compilation.status()).as(compilation.diagnostics().toString())
                .isEqualTo(Compilation.Status.SUCCESS);
        assertThat(collisionErrors(compilation)).isEmpty();
    }

    @Test
    void nonCollidingToolNamesAreUnchanged() throws IOException {
        JavaFileObject service = JavaFileObjects.forSourceString("test.CatalogService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class CatalogService {
                    @AgenticExposed(description = "Item by id")
                    public String findItem(Long id) { return null; }
                    @AgenticExposed(description = "Item count", toolName = "countItems")
                    public long count() { return 0; }
                }
                """);

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(service);

        assertThat(compilation.status()).as(compilation.diagnostics().toString())
                .isEqualTo(Compilation.Status.SUCCESS);
        String tool = generatedSource(compilation, "test.generated.CatalogServiceMcpTool");
        assertThat(tool).contains("name = \"findItem\"").contains("name = \"countItems\"");
    }

    private static JavaFileObject service(String pkg, String prefix) {
        return JavaFileObjects.forSourceString(pkg + "." + prefix + "Service",
                """
                package %s;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class %sService {
                    @AgenticExposed(description = "Lookup")
                    public String lookup(Long id) { return null; }
                }
                """.formatted(pkg, prefix));
    }

    private static List<Diagnostic<? extends JavaFileObject>> collisionErrors(Compilation compilation) {
        return compilation.errors().stream()
                .filter(error -> error.getMessage(null).contains(COLLISION_MARKER))
                .toList();
    }

    private static String generatedSource(Compilation compilation, String qualifiedName) throws IOException {
        return compilation.generatedSourceFile(qualifiedName).orElseThrow().getCharContent(true).toString();
    }
}
