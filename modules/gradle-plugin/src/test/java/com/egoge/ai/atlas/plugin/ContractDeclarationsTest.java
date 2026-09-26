/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of how {@link ContractDeclarations} reads class files that javac compiles here, against
 * stand-ins for the two annotation types with their binary names and {@code RUNTIME} retention.
 */
class ContractDeclarationsTest {

    private static final String EXPOSED = """
            package com.egoge.ai.atlas.annotations;

            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
            public @interface AgenticExposed {
                String description() default "";
            }
            """;
    private static final String ENTITY = """
            package com.egoge.ai.atlas.annotations;

            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
            public @interface AgenticEntity {
            }
            """;
    private static final String DECLARING = """
            package test;

            @com.egoge.ai.atlas.annotations.AgenticExposed(description = "Orders")
            public class Declaring {
            }
            """;

    @TempDir
    Path tempDir;

    private Path stubs;
    private Path classes;
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void compileStubs() throws IOException {
        stubs = tempDir.resolve("stubs");
        classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        compile(stubs, null, EXPOSED, ENTITY);
    }

    @Test
    void aTopLevelClassDeclares() throws IOException {
        compile(classes, stubs, DECLARING);

        assertThat(declared()).isTrue();
    }

    @Test
    void aStaticNestedMemberClassDeclares() throws IOException {
        compile(classes, stubs, """
                package test;

                public class Outer {
                    @com.egoge.ai.atlas.annotations.AgenticEntity
                    public static class Nested {
                    }
                }
                """);

        assertThat(declared()).isTrue();
    }

    @Test
    void anInnerMemberClassDeclares() throws IOException {
        compile(classes, stubs, """
                package test;

                public class Outer {
                    public class Inner {
                        @com.egoge.ai.atlas.annotations.AgenticExposed(description = "Find")
                        public String find(Long id) { return null; }
                    }
                }
                """);

        assertThat(declared()).isTrue();
    }

    @Test
    void anAnonymousClassDoesNotDeclare() throws IOException {
        compile(classes, stubs, """
                package test;

                public class Outer {
                    Runnable task = new Runnable() {
                        @com.egoge.ai.atlas.annotations.AgenticExposed(description = "Run")
                        public void run() { }
                    };
                }
                """);

        assertThat(declared()).isFalse();
        assertThat(warnings).isEmpty();
    }

    @Test
    void aLocalClassDoesNotDeclare() throws IOException {
        compile(classes, stubs, """
                package test;

                public class Outer {
                    Object make() {
                        @com.egoge.ai.atlas.annotations.AgenticEntity
                        class Local {
                        }
                        return new Local();
                    }
                }
                """);

        assertThat(declared()).isFalse();
    }

    @Test
    void aMemberClassOfAnAnonymousClassDoesNotDeclare() throws IOException {
        compile(classes, stubs, """
                package test;

                public class Outer {
                    Object task = new Object() {
                        @com.egoge.ai.atlas.annotations.AgenticEntity
                        class Member {
                        }
                    };
                }
                """);

        assertThat(declared()).isFalse();
    }

    @Test
    void aFieldOfTheAnnotationTypeDoesNotDeclare() throws IOException {
        compile(classes, stubs, """
                package test;

                public class Outer {
                    private com.egoge.ai.atlas.annotations.AgenticExposed kept;
                }
                """);

        assertThat(declared()).isFalse();
    }

    @Test
    void wideConstantsRecordsAndRichAnnotationValuesAreRead() throws IOException {
        compile(classes, stubs, """
                package test;

                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                public record Rich(long id, double amount) {

                    public static final long BIG = 1234567890123L;
                    public static final double RATE = 3.25;

                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface Values {
                        String[] names();
                        RetentionPolicy policy();
                        Class<?> type();
                        Deprecated nested();
                        long[] longs();
                    }

                    @Values(names = { "a", "b" }, policy = RetentionPolicy.CLASS, type = String.class,
                            nested = @Deprecated(since = "1"), longs = { 9876543210L })
                    @com.egoge.ai.atlas.annotations.AgenticExposed(description = "Total")
                    public double total() { return id * amount * RATE + BIG; }
                }
                """);

        assertThat(declared()).isTrue();
        assertThat(warnings).isEmpty();
    }

    @Test
    void anUnreadableClassFileAloneDeclaresNothingWithAWarning() throws IOException {
        writeUnknownConstantPoolTag();

        assertThat(declared()).isFalse();
        assertThat(warnings).singleElement().asString().contains("Future.class")
                .contains("unknown constant pool tag 99");
    }

    @Test
    void anUnreadableClassFileNextToADeclaringOneDeclares() throws IOException {
        writeUnknownConstantPoolTag();
        compile(classes, stubs, DECLARING);

        assertThat(declared()).isTrue();
    }

    private boolean declared() {
        return ContractDeclarations.declared(classes.toFile(), warnings::add);
    }

    /** A class file whose first constant pool entry has a tag no JVMS version defines. */
    private void writeUnknownConstantPoolTag() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // minor version
            out.writeShort(99); // major version
            out.writeShort(2); // constant pool count
            out.writeByte(99); // unknown tag
            out.writeShort(0);
        }
        Path file = classes.resolve("test/Future.class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes.toByteArray());
    }

    /** Compiles each source, named after its public type, into {@code outputDir}. */
    private void compile(Path outputDir, Path classpath, String... sources) throws IOException {
        Path sourceDir = Files.createTempDirectory(tempDir, "src");
        List<String> arguments = new ArrayList<>(List.of("-d", outputDir.toString(), "-proc:none"));
        if (classpath != null) {
            arguments.addAll(List.of("-cp", classpath.toString()));
        }
        for (String source : sources) {
            String pkg = source.lines().filter(l -> l.startsWith("package ")).findFirst().orElseThrow()
                    .replaceAll("package |;", "");
            String type = source.lines().map(String::strip).filter(l -> l.startsWith("public "))
                    .findFirst().orElseThrow().replaceAll(".*(class|record|@interface) (\\w+).*", "$2");
            Path file = sourceDir.resolve(pkg.replace('.', File.separatorChar)).resolve(type + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
            arguments.add(file.toString());
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exit = javac.run(null, null, errors, arguments.toArray(String[]::new));
        assertThat(exit).as(errors.toString()).isZero();
        try (Stream<Path> walk = Files.walk(outputDir)) {
            assertThat(walk.filter(p -> p.toString().endsWith(".class"))).isNotEmpty();
        }
    }
}
