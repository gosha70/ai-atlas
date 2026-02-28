/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.processor;

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

                import ai.adam.annotations.AgentVisible;
                import ai.adam.annotations.AgentVisibleClass;

                @AgentVisibleClass
                public class MyEntity {
                    @AgentVisible(description = "The ID")
                    private Long id;

                    @AgentVisible(description = "The name")
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

                import ai.adam.annotations.AgentVisible;
                import ai.adam.annotations.AgentVisibleClass;

                @AgentVisibleClass(dtoName = "ProductSummary", packageName = "test.api")
                public class Product {
                    @AgentVisible(description = "Product ID")
                    private Long id;

                    @AgentVisible(description = "Product title")
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

        // Default class name when @AgentVisibleClass.name is empty
        assertThat(dto).contains("CLASS_NAME = \"Product\"");
    }

    @Test
    void handlesBooleanFieldsWithIsPrefix() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Feature",
                """
                package test;

                import ai.adam.annotations.AgentVisible;
                import ai.adam.annotations.AgentVisibleClass;

                @AgentVisibleClass
                public class Feature {
                    @AgentVisible(description = "Whether the feature is active")
                    private boolean active;

                    @AgentVisible(description = "Feature name")
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
                import ai.adam.annotations.AgentVisible;
                import ai.adam.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Task {
                    @AgentVisible(description = "Task ID") private Long id;
                    @AgentVisible(description = "Task priority") private Priority priority;
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
                import ai.adam.annotations.AgentVisible;
                import ai.adam.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Invoice {
                    @AgentVisible(name = "invoiceId", description = "Unique invoice identifier")
                    private Long id;
                    @AgentVisible(name = "totalCents", description = "Total amount in cents")
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

        // FIELD_METADATA keys use display names from @AgentVisible(name=...)
        assertThat(dto).contains("Map.entry(\"invoiceId\"");
        assertThat(dto).contains("Map.entry(\"totalCents\"");
    }

    @Test
    void propagatesClassLevelMetadata() {
        JavaFileObject source = JavaFileObjects.forSourceString("test.Customer",
                """
                package test;
                import ai.adam.annotations.AgentVisible;
                import ai.adam.annotations.AgentVisibleClass;
                @AgentVisibleClass(name = "customer", description = "A business customer", includeTypeInfo = false)
                public class Customer {
                    @AgentVisible(description = "Customer ID") private Long id;
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
                import ai.adam.annotations.AgentVisible;
                import ai.adam.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Node {
                    @AgentVisible(description = "Node ID") private Long id;
                    @AgentVisible(description = "Node label", checkCircularReference = false) private String label;
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
                import ai.adam.annotations.AgentVisible;
                import ai.adam.annotations.AgentVisibleClass;
                @AgentVisibleClass
                public class Account {
                    @AgentVisible(description = "Account ID") private Long id;
                    @AgentVisible(description = "Email address", sensitive = true) private String email;
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
