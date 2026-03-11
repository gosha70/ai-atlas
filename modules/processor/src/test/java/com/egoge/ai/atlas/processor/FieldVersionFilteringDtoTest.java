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
import static org.assertj.core.api.Assertions.assertThat;

class FieldVersionFilteringDtoTest {

    private static Compilation compile(int apiMajor, JavaFileObject... sources) {
        return javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=" + apiMajor)
                .compile(sources);
    }

    private static String getDto(Compilation compilation, String qualifiedName) {
        var file = compilation.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains(qualifiedName.replace('.', '/') + ".java"))
                .findFirst();
        assertThat(file).as("Generated source " + qualifiedName).isPresent();
        try {
            return file.get().getCharContent(true).toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void defaultAnnotation_unchangedOutput() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID") private Long id;
                    public Long getId() { return id; }
                }
                """);

        Compilation compilation = compile(1, source);
        assertThat(compilation).succeeded();
        String dto = getDto(compilation, "test.generated.OrderDto");
        assertThat(dto).contains("record OrderDto(Long id)");
        assertThat(dto).contains("boolean deprecated");
        assertThat(dto).contains("String deprecatedMessage");
        // Defaults: deprecated=false, message=""
        assertThat(dto).contains("false, \"\"");
    }

    @Test
    void fieldNotYetIntroduced_excludedFromDto() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID") private Long id;
                    @AgenticField(description = "Ref", sinceVersion = 2) private String ref;
                    public Long getId() { return id; }
                    public String getRef() { return ref; }
                }
                """);

        Compilation compilation = compile(1, source);
        assertThat(compilation).succeeded();
        String dto = getDto(compilation, "test.generated.OrderDto");
        assertThat(dto).contains("record OrderDto(Long id)");
        assertThat(dto).doesNotContain("String ref");
    }

    @Test
    void fieldIntroduced_includedInDto() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID") private Long id;
                    @AgenticField(description = "Ref", sinceVersion = 2) private String ref;
                    public Long getId() { return id; }
                    public String getRef() { return ref; }
                }
                """);

        Compilation compilation = compile(2, source);
        assertThat(compilation).succeeded();
        String dto = getDto(compilation, "test.generated.OrderDto");
        assertThat(dto).contains("record OrderDto(Long id, String ref)");
    }

    @Test
    void fieldRemoved_excludedFromDto() {
        // removedInVersion=2 with major=2 → excluded (exclusive half-open semantics)
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
        String dto = getDto(compilation, "test.generated.OrderDto");
        assertThat(dto).contains("record OrderDto(Long id)");
        assertThat(dto).doesNotContain("oldField");
    }

    @Test
    void fieldAtRemovalBoundaryMinusOne_included() {
        // removedInVersion=2 with major=1 → included
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

        Compilation compilation = compile(1, source);
        assertThat(compilation).succeeded();
        String dto = getDto(compilation, "test.generated.OrderDto");
        assertThat(dto).contains("record OrderDto(Long id, String oldField)");
    }

    @Test
    void deprecatedField_presentWithDeprecationMetadata() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID") private Long id;
                    @AgenticField(description = "Name", deprecatedSinceVersion = 2,
                                  deprecatedMessage = "Use orderRef")
                    private String customerName;
                    public Long getId() { return id; }
                    public String getCustomerName() { return customerName; }
                }
                """);

        Compilation compilation = compile(2, source);
        assertThat(compilation).succeeded();
        String dto = getDto(compilation, "test.generated.OrderDto");
        // Field is present
        assertThat(dto).contains("record OrderDto(Long id, String customerName)");
        // FieldMeta has computed deprecated=true and message
        assertThat(dto).contains("true, \"Use orderRef\"");
    }

    @Test
    void deprecatedFieldNotYetDeprecated_noDeprecationMetadata() {
        // deprecatedSinceVersion=3, major=2 → not yet deprecated for this build
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID") private Long id;
                    @AgenticField(description = "Name", deprecatedSinceVersion = 3,
                                  deprecatedMessage = "Use orderRef")
                    private String customerName;
                    public Long getId() { return id; }
                    public String getCustomerName() { return customerName; }
                }
                """);

        Compilation compilation = compile(2, source);
        assertThat(compilation).succeeded();
        String dto = getDto(compilation, "test.generated.OrderDto");
        assertThat(dto).contains("record OrderDto(Long id, String customerName)");
        // Not yet deprecated at major=2 — computed state is false/""
        assertThat(dto).contains("false, \"\"");
        assertThat(dto).doesNotContain("true, \"Use orderRef\"");
    }

    @Test
    void allFieldsFilteredOut_unreferencedEntity_warning() {
        // Entity with only sinceVersion=2 field, compiled at major=1 → warning only, no error
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "Ref", sinceVersion = 2) private String ref;
                    public String getRef() { return ref; }
                }
                """);

        Compilation compilation = compile(1, source);
        assertThat(compilation).succeeded();
        assertThat(compilation).hadWarningContaining("has no active @AgenticField fields for apiMajor=1");
        // No DTO generated
        assertThat(compilation.generatedSourceFiles()).noneMatch(
                f -> f.getName().contains("OrderDto"));
    }

    @Test
    void allFieldsFilteredOut_referencedByService_error() {
        JavaFileObject entity = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "Ref", sinceVersion = 2) private String ref;
                    public String getRef() { return ref; }
                }
                """);
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                public class OrderService {
                    @AgenticExposed(description = "get order", returnType = Order.class)
                    public Order getOrder() { return null; }
                }
                """);

        Compilation compilation = compile(1, entity, service);
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("has no active fields for apiMajor=1");
    }

    @Test
    void allFieldsFilteredOut_referencedByNestedEntity_error() {
        JavaFileObject item = JavaFileObjects.forSourceString("test.Item",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Item {
                    @AgenticField(description = "Name", sinceVersion = 2) private String name;
                    public String getName() { return name; }
                }
                """);
        JavaFileObject order = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.List;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID") private Long id;
                    @AgenticField(description = "Items") private List<Item> items;
                    public Long getId() { return id; }
                    public List<Item> getItems() { return items; }
                }
                """);

        Compilation compilation = compile(1, item, order);
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("references entity Item which has no active fields for apiMajor=1");
    }

    @Test
    void mixedVersionFields_correctSubset() {
        // Fields: id(since=1), old(since=1,removed=2), ref(since=2), future(since=3)
        // At major=2: id + ref only
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID") private Long id;
                    @AgenticField(description = "Old", removedInVersion = 2) private String old;
                    @AgenticField(description = "Ref", sinceVersion = 2) private String ref;
                    @AgenticField(description = "Future", sinceVersion = 3) private String future;
                    public Long getId() { return id; }
                    public String getOld() { return old; }
                    public String getRef() { return ref; }
                    public String getFuture() { return future; }
                }
                """);

        Compilation compilation = compile(2, source);
        assertThat(compilation).succeeded();
        String dto = getDto(compilation, "test.generated.OrderDto");
        assertThat(dto).contains("record OrderDto(Long id, String ref)");
        assertThat(dto).doesNotContain("old");
        assertThat(dto).doesNotContain("future");
    }

    @Test
    void fieldReplacement_cleanHandoff() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID") private Long id;
                    @AgenticField(description = "Old name", sinceVersion = 1, removedInVersion = 3)
                    private String oldName;
                    @AgenticField(description = "New name", sinceVersion = 3)
                    private String newName;
                    public Long getId() { return id; }
                    public String getOldName() { return oldName; }
                    public String getNewName() { return newName; }
                }
                """);

        // major=2 → id + oldName only
        Compilation compilationV2 = compile(2, source);
        assertThat(compilationV2).succeeded();
        String dtoV2 = getDto(compilationV2, "test.generated.OrderDto");
        assertThat(dtoV2).contains("record OrderDto(Long id, String oldName)");
        assertThat(dtoV2).doesNotContain("newName");

        // major=3 → id + newName only
        Compilation compilationV3 = compile(3, source);
        assertThat(compilationV3).succeeded();
        String dtoV3 = getDto(compilationV3, "test.generated.OrderDto");
        assertThat(dtoV3).contains("record OrderDto(Long id, String newName)");
        assertThat(dtoV3).doesNotContain("oldName");
    }

    @Test
    void serviceReturnsEntity_whoseDtoSkippedDueToNestedEmptyRef_error() {
        JavaFileObject item = JavaFileObjects.forSourceString("test.Item",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Item {
                    @AgenticField(description = "Name", sinceVersion = 2) private String name;
                    public String getName() { return name; }
                }
                """);
        JavaFileObject order = JavaFileObjects.forSourceString("test.Order",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.List;
                @AgenticEntity
                public class Order {
                    @AgenticField(description = "ID") private Long id;
                    @AgenticField(description = "Items") private List<Item> items;
                    public Long getId() { return id; }
                    public List<Item> getItems() { return items; }
                }
                """);
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Find order")
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = compile(1, item, order, service);
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("nested references to empty entities");
    }

    @Test
    void hintedCollectionToEmptyEntity_error() {
        JavaFileObject child = JavaFileObjects.forSourceString("test.Child",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity
                public class Child {
                    @AgenticField(description = "Tag", sinceVersion = 3) private String tag;
                    public String getTag() { return tag; }
                }
                """);
        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent",
                """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.Collection;
                @AgenticEntity
                public class Parent {
                    @AgenticField(description = "ID") private Long id;
                    @AgenticField(description = "Children", type = Child.class)
                    private Collection children;
                    public Long getId() { return id; }
                    public Collection getChildren() { return children; }
                }
                """);

        Compilation compilation = compile(1, child, parent);
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining(
                "references entity Child which has no active fields for apiMajor=1");
    }
}
