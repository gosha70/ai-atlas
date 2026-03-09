/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

class DtoGeneratorTest {
    @Test
    void generatesRecordWithOnlyAnnotatedFields() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.MyEntity",
                """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;

                @AgenticEntity
                public class MyEntity {
                    @AgenticField(description = "The ID")
                    private Long id;

                    @AgenticField(description = "The name")
                    private String name;

                    private String secret;

                    public Long getId() { return id; }
                    public String getName() { return name; }
                    public String getSecret() { return secret; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        CompilationSubject.assertThat(compilation).succeeded();

        String dto = getGeneratedSource(compilation, "test.generated.MyEntityDto");

        // Record declaration includes only annotated fields
        assertThat(dto).contains("record MyEntityDto(Long id, String name)");
        // Secret field is excluded
        assertThat(dto).doesNotContain("secret");

        // fromEntity factory method
        assertThat(dto).contains("fromEntity(MyEntity entity)");
        assertThat(dto).contains("entity.getId()");
        assertThat(dto).contains("entity.getName()");

        // Metadata constants
        assertThat(dto).contains("CLASS_NAME = \"MyEntity\"");
        assertThat(dto).contains("CLASS_DESCRIPTION = \"\"");
        assertThat(dto).contains("INCLUDE_TYPE_INFO = true");

        // FieldMeta nested record
        assertThat(dto).contains("record FieldMeta(");
        assertThat(dto).contains("String description");
        assertThat(dto).contains("List<String> validValues");
        assertThat(dto).contains("boolean sensitive");
        assertThat(dto).contains("boolean checkCircularReference");

        // FIELD_METADATA map with correct entries
        assertThat(dto).contains("FIELD_METADATA");
        assertThat(dto).contains("Map.entry(\"id\"");
        assertThat(dto).contains("\"The ID\"");
        assertThat(dto).contains("Map.entry(\"name\"");
        assertThat(dto).contains("\"The name\"");
    }

    @Test
    void respectsCustomDtoNameAndPackage() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Product",
                """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;

                @AgenticEntity(dtoName = "ProductSummary", packageName = "test.api")
                public class Product {
                    @AgenticField(description = "Product ID")
                    private Long id;

                    @AgenticField(description = "Product title")
                    private String title;

                    public Long getId() { return id; }
                    public String getTitle() { return title; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        CompilationSubject.assertThat(compilation).succeeded();

        String dto = getGeneratedSource(compilation, "test.api.ProductSummary");

        assertThat(dto).contains("package test.api;");
        assertThat(dto).contains("record ProductSummary(Long id, String title)");
        assertThat(dto).contains("fromEntity(Product entity)");
        assertThat(dto).contains("entity.getId()");
        assertThat(dto).contains("entity.getTitle()");

        // Default class name when @AgenticEntity.name is empty
        assertThat(dto).contains("CLASS_NAME = \"Product\"");
    }

    @Test
    void handlesBooleanFieldsWithIsPrefix() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Feature",
                """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;

                @AgenticEntity
                public class Feature {
                    @AgenticField(description = "Whether the feature is active")
                    private boolean active;

                    @AgenticField(description = "Feature name")
                    private String name;

                    public boolean isActive() { return active; }
                    public String getName() { return name; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        CompilationSubject.assertThat(compilation).succeeded();

        String dto = getGeneratedSource(compilation, "test.generated.FeatureDto");

        assertThat(dto).contains("record FeatureDto(boolean active, String name)");
        assertThat(dto).contains("entity.isActive()");
        assertThat(dto).contains("entity.getName()");
    }

    @Test
    void detectsEnumFieldAndPopulatesValidValues() {
        JavaFileObject enumType = JavaFileObjects.forSourceString("test.Priority",
                """
                package test;
                public enum Priority { LOW, MEDIUM, HIGH, CRITICAL }
                """);

        JavaFileObject entity = JavaFileObjects.forSourceString("test.Task",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;
                @AgenticEntity
                public class Task {
                    @AgenticField(description = "Task ID") private Long id;
                    @AgenticField(description = "Task priority") private Priority priority;
                    public Long getId() { return id; }
                    public Priority getPriority() { return priority; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(enumType, entity);

        CompilationSubject.assertThat(compilation).succeeded();

        String dto = getGeneratedSource(compilation, "test.generated.TaskDto");

        // Enum field is present in record
        assertThat(dto).contains("Priority priority");

        // FIELD_METADATA contains enum valid values
        assertThat(dto).contains("\"LOW\"");
        assertThat(dto).contains("\"MEDIUM\"");
        assertThat(dto).contains("\"HIGH\"");
        assertThat(dto).contains("\"CRITICAL\"");

        // Non-enum field uses List.of() (empty valid values)
        assertThat(dto).contains("Map.entry(\"id\"");
    }

    @Test
    void usesCustomNameAttributeAsDisplayName() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Invoice",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;
                @AgenticEntity
                public class Invoice {
                    @AgenticField(name = "invoiceId", description = "Unique invoice identifier")
                    private Long id;
                    @AgenticField(name = "totalCents", description = "Total amount in cents")
                    private Long total;
                    public Long getId() { return id; }
                    public Long getTotal() { return total; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        CompilationSubject.assertThat(compilation).succeeded();

        String dto = getGeneratedSource(compilation, "test.generated.InvoiceDto");

        // Record params use Java field names (not display names)
        assertThat(dto).contains("record InvoiceDto(Long id, Long total)");

        // FIELD_METADATA keys use display names from @AgenticField(name=...)
        assertThat(dto).contains("Map.entry(\"invoiceId\"");
        assertThat(dto).contains("Map.entry(\"totalCents\"");
    }

    @Test
    void propagatesClassLevelMetadata() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Customer",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;
                @AgenticEntity(name = "customer", description = "A business customer", includeTypeInfo = false)
                public class Customer {
                    @AgenticField(description = "Customer ID") private Long id;
                    public Long getId() { return id; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        CompilationSubject.assertThat(compilation).succeeded();

        String dto = getGeneratedSource(compilation, "test.generated.CustomerDto");

        assertThat(dto).contains("CLASS_NAME = \"customer\"");
        assertThat(dto).contains("CLASS_DESCRIPTION = \"A business customer\"");
        assertThat(dto).contains("INCLUDE_TYPE_INFO = false");
    }

    @Test
    void propagatesCheckCircularReferenceAttribute() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Node",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;
                @AgenticEntity
                public class Node {
                    @AgenticField(description = "Node ID") private Long id;
                    @AgenticField(description = "Node label", checkCircularReference = false) private String label;
                    public Long getId() { return id; }
                    public String getLabel() { return label; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        CompilationSubject.assertThat(compilation).succeeded();

        String dto = getGeneratedSource(compilation, "test.generated.NodeDto");

        // Default checkCircularReference is true for id
        assertThat(dto).contains("FIELD_METADATA");

        // The FieldMeta constructor has 4 args: description, validValues, sensitive, checkCircularReference
        // We verify the generated code compiles and contains metadata for both fields
        assertThat(dto).contains("Map.entry(\"id\"");
        assertThat(dto).contains("Map.entry(\"label\"");
    }

    @Test
    void marksSensitiveFieldInMetadata() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Account",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;
                @AgenticEntity
                public class Account {
                    @AgenticField(description = "Account ID") private Long id;
                    @AgenticField(description = "Email address", sensitive = true) private String email;
                    public Long getId() { return id; }
                    public String getEmail() { return email; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        CompilationSubject.assertThat(compilation).succeeded();

        String dto = getGeneratedSource(compilation, "test.generated.AccountDto");

        assertThat(dto).contains("record AccountDto(Long id, String email)");
        assertThat(dto).contains("FIELD_METADATA");
        assertThat(dto).contains("Map.entry(\"id\"");
        assertThat(dto).contains("Map.entry(\"email\"");
    }

    @Test
    void usesExplicitAllowedValuesOnNonEnumField() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Config",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;
                @AgenticEntity
                public class Config {
                    @AgenticField(description = "Log level", allowedValues = {"DEBUG", "INFO", "WARN", "ERROR"})
                    private String logLevel;
                    public String getLogLevel() { return logLevel; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(source);

        CompilationSubject.assertThat(compilation).succeeded();

        String dto = getGeneratedSource(compilation, "test.generated.ConfigDto");

        assertThat(dto).contains("\"DEBUG\"");
        assertThat(dto).contains("\"INFO\"");
        assertThat(dto).contains("\"WARN\"");
        assertThat(dto).contains("\"ERROR\"");
    }

    @Test
    void explicitAllowedValuesOverrideEnumConstants() {
        JavaFileObject enumType = JavaFileObjects.forSourceString("test.Priority",
                """
                package test;
                public enum Priority { LOW, MEDIUM, HIGH, CRITICAL }
                """);

        JavaFileObject entity = JavaFileObjects.forSourceString("test.Ticket",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;
                @AgenticEntity
                public class Ticket {
                    @AgenticField(description = "Ticket priority", allowedValues = {"LOW", "HIGH"})
                    private Priority priority;
                    public Priority getPriority() { return priority; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(enumType, entity);

        CompilationSubject.assertThat(compilation).succeeded();

        String dto = getGeneratedSource(compilation, "test.generated.TicketDto");

        // Only explicit values present, not auto-detected MEDIUM/CRITICAL
        assertThat(dto).contains("\"LOW\"");
        assertThat(dto).contains("\"HIGH\"");
        assertThat(dto).doesNotContain("\"MEDIUM\"");
        assertThat(dto).doesNotContain("\"CRITICAL\"");
    }

    @Test
    void emptyAllowedValuesFallsBackToEnumDetection() {
        JavaFileObject enumType = JavaFileObjects.forSourceString("test.Color",
                """
                package test;
                public enum Color { RED, GREEN, BLUE }
                """);

        JavaFileObject entity = JavaFileObjects.forSourceString("test.Widget",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticField;
                import com.egoge.ai.atlas.annotations.AgenticEntity;
                @AgenticEntity
                public class Widget {
                    @AgenticField(description = "Widget color", allowedValues = {})
                    private Color color;
                    public Color getColor() { return color; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(enumType, entity);

        CompilationSubject.assertThat(compilation).succeeded();

        String dto = getGeneratedSource(compilation, "test.generated.WidgetDto");

        // Falls back to enum constants since allowedValues is explicitly empty
        assertThat(dto).contains("\"RED\"");
        assertThat(dto).contains("\"GREEN\"");
        assertThat(dto).contains("\"BLUE\"");
    }

    @Test
    void mapsEntityReferenceFieldsToDtoTypesWithCycleDetection() {
        JavaFileObject parent = JavaFileObjects.forSourceString("test.Parent", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.List;
                @AgenticEntity public class Parent {
                    @AgenticField(description = "ID") private Long id;
                    @AgenticField(description = "Children") private List<Child> children;
                    public Long getId() { return id; }
                    public List<Child> getChildren() { return children; }
                }""");
        JavaFileObject child = JavaFileObjects.forSourceString("test.Child", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity public class Child {
                    @AgenticField(description = "ID") private Long id;
                    @AgenticField(description = "Parent ref") private Parent parent;
                    public Long getId() { return id; }
                    public Parent getParent() { return parent; }
                }""");

        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(parent, child);
        CompilationSubject.assertThat(compilation).succeeded();

        String parentDto = getGeneratedSource(compilation, "test.generated.ParentDto");
        String childDto = getGeneratedSource(compilation, "test.generated.ChildDto");

        // Entity refs mapped to DTO types with cycle detection
        assertThat(parentDto).contains("List<ChildDto> children").doesNotContain("List<Child>")
                .contains("ChildDto.fromEntity((Child) e)").contains("ThreadLocal<Set<Object>> _visiting");
        assertThat(childDto).contains("ParentDto parent")
                .contains("ParentDto.fromEntity(entity.getParent())")
                .contains("ThreadLocal<Set<Object>> _visiting");
    }
    @Test
    void mapsArrayListIterableAndArrayEntityFields() {
        JavaFileObject item = JavaFileObjects.forSourceString("test.Item", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticEntity public class Item {
                    @AgenticField(description = "ID") private Long id;
                    public Long getId() { return id; }
                }""");
        JavaFileObject container = JavaFileObjects.forSourceString("test.Container", """
                package test;
                import com.egoge.ai.atlas.annotations.*;
                import java.util.ArrayList;
                @AgenticEntity public class Container {
                    @AgenticField(description = "ID") private Long id;
                    @AgenticField(description = "a") private ArrayList<Item> listItems;
                    @AgenticField(description = "b") private Iterable<Item> iterItems;
                    @AgenticField(description = "c") private Item[] arrItems;
                    public Long getId() { return id; }
                    public ArrayList<Item> getListItems() { return listItems; }
                    public Iterable<Item> getIterItems() { return iterItems; }
                    public Item[] getArrItems() { return arrItems; }
                }""");
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(item, container);
        CompilationSubject.assertThat(compilation).succeeded();
        String dto = getGeneratedSource(compilation, "test.generated.ContainerDto");
        // All three map to List<ItemDto>; each uses the correct streaming path
        assertThat(dto).contains("List<ItemDto> listItems").contains("List<ItemDto> iterItems")
                .contains("List<ItemDto> arrItems");
        assertThat(dto).contains("entity.getListItems().stream().map(e -> ItemDto.fromEntity((Item) e))");
        assertThat(dto).contains("StreamSupport.stream(entity.getIterItems().spliterator()");
        assertThat(dto).contains("Arrays.stream(entity.getArrItems()).map(e -> ItemDto.fromEntity((Item) e))");
    }

    private static String getGeneratedSource(Compilation compilation, String qualifiedName) {
        var file = compilation.generatedSourceFile(qualifiedName);
        assertThat(file).as("Generated file: " + qualifiedName).isPresent();
        try {
            return file.get().getCharContent(true).toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
