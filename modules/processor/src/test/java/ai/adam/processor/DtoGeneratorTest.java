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

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("test.generated.MyEntityDto")
                .hasSourceEquivalentTo(JavaFileObjects.forSourceString("test.generated.MyEntityDto",
                        """
                        package test.generated;

                        import java.lang.Long;
                        import java.lang.String;
                        import javax.annotation.processing.Generated;
                        import test.MyEntity;

                        @Generated("ai.adam.processor")
                        public record MyEntityDto(Long id, String name) {
                            public static MyEntityDto fromEntity(MyEntity entity) {
                                if (entity == null) return null;
                                return new MyEntityDto(
                                        entity.getId(),
                                        entity.getName()
                                        );
                            }
                        }
                        """));
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

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("test.api.ProductSummary")
                .hasSourceEquivalentTo(JavaFileObjects.forSourceString("test.api.ProductSummary",
                        """
                        package test.api;

                        import java.lang.Long;
                        import java.lang.String;
                        import javax.annotation.processing.Generated;
                        import test.Product;

                        @Generated("ai.adam.processor")
                        public record ProductSummary(Long id, String title) {
                            public static ProductSummary fromEntity(Product entity) {
                                if (entity == null) return null;
                                return new ProductSummary(
                                        entity.getId(),
                                        entity.getTitle()
                                        );
                            }
                        }
                        """));
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

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("test.generated.FeatureDto")
                .hasSourceEquivalentTo(JavaFileObjects.forSourceString("test.generated.FeatureDto",
                        """
                        package test.generated;

                        import java.lang.String;
                        import javax.annotation.processing.Generated;
                        import test.Feature;

                        @Generated("ai.adam.processor")
                        public record FeatureDto(boolean active, String name) {
                            public static FeatureDto fromEntity(Feature entity) {
                                if (entity == null) return null;
                                return new FeatureDto(
                                        entity.isActive(),
                                        entity.getName()
                                        );
                            }
                        }
                        """));
    }
}
