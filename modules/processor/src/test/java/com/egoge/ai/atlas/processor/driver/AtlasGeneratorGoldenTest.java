/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.driver;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.egoge.ai.atlas.processor.generator.OpenApiGenerator;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Stream;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Golden tests for the {@link AtlasGenerator} driver (FR-002, FR-009).
 *
 * <p>Two references are checked: an in-process annotation-processing run over the same sources
 * (always available), and the real {@code :demo:compileJava} output wired in by Gradle.
 */
class AtlasGeneratorGoldenTest {

    private static final String GENERATED_MARKER = "@Generated(\"com.egoge.ai.atlas.processor\")";
    private static final String JAVA_SUFFIX = ".java";
    private static final String SOURCE_OUTPUT_PREFIX = "/SOURCE_OUTPUT/";
    private static final String OPTION_PREFIX = "option.";
    private static final String CLASSPATH_KEY = "classpath";
    private static final String DEMO_SOURCES_PROPERTY = "ai.atlas.demo.sources";
    private static final String DEMO_GENERATED_PROPERTY = "ai.atlas.demo.generatedSources";
    private static final String DEMO_INPUTS_PROPERTY = "ai.atlas.demo.generationInputs";
    private static final String DEFAULT_OPENAPI_PATH = OpenApiGenerator.RESOURCE_DIR + "openapi-v1.json";

    private static final String ENTITY_SOURCE = """
            package test;

            import com.egoge.ai.atlas.annotations.AgenticEntity;
            import com.egoge.ai.atlas.annotations.AgenticField;

            @AgenticEntity(description = "A customer of the shop")
            public class Customer {
                @AgenticField(description = "Unique identifier")
                private Long id;

                @AgenticField(description = "Display name")
                private String name;

                private String socialSecurityNumber;

                public Long getId() { return id; }
                public String getName() { return name; }
                public String getSocialSecurityNumber() { return socialSecurityNumber; }
            }
            """;

    private static final String SERVICE_SOURCE = """
            package test;

            import com.egoge.ai.atlas.annotations.AgenticExposed;
            import java.util.List;

            @AgenticExposed(description = "Look customers up", returnType = Customer.class)
            public class CustomerService {
                public Customer findById(Long id) { return null; }
                public List<Customer> findAll() { return List.of(); }
            }
            """;

    @TempDir
    private Path outputDir;

    @Test
    void driverOutputMatchesInProcessAnnotationProcessing(@TempDir Path sourceDir) throws IOException {
        GenerationResult result = generateFromSourceStrings(sourceDir);

        Compilation reference = javac()
                .withProcessors(new AgenticProcessor())
                .compile(JavaFileObjects.forSourceString("test.Customer", ENTITY_SOURCE),
                        JavaFileObjects.forSourceString("test.CustomerService", SERVICE_SOURCE));
        CompilationSubject.assertThat(reference).succeeded();

        Map<String, String> expected = new TreeMap<>();
        for (JavaFileObject file : reference.generatedSourceFiles()) {
            expected.put(stripPrefix(file.getName(), SOURCE_OUTPUT_PREFIX), read(file));
        }

        assertThat(expected).as("reference produced generated sources").isNotEmpty();
        assertThat(generatedSourcesByPath(result)).isEqualTo(expected);

        JavaFileObject referenceSpec = reference
                .generatedFile(StandardLocation.CLASS_OUTPUT, "", DEFAULT_OPENAPI_PATH)
                .orElseThrow(() -> new AssertionError("reference emitted no OpenAPI spec"));
        assertThat(result.openApi()).isEqualTo(read(referenceSpec));
    }

    @Test
    void driverReproducesDemoBuildOutputByteForByte() throws IOException {
        Path demoSources = pathProperty(DEMO_SOURCES_PROPERTY);
        Path demoGenerated = pathProperty(DEMO_GENERATED_PROPERTY);
        Path generationInputs = pathProperty(DEMO_INPUTS_PROPERTY);
        assumeTrue(demoSources != null && demoGenerated != null && generationInputs != null,
                "demo golden inputs are wired by Gradle; skipping standalone run");
        assumeTrue(Files.isDirectory(demoGenerated) && Files.isRegularFile(generationInputs),
                "demo has not been compiled yet; skipping");

        Properties inputs = load(generationInputs);
        Map<String, String> processorOptions = new TreeMap<>();
        for (String key : inputs.stringPropertyNames()) {
            if (key.startsWith(OPTION_PREFIX)) {
                processorOptions.put(key.substring(OPTION_PREFIX.length()), inputs.getProperty(key));
            }
        }

        GenerationResult result = AtlasGenerator.generate(List.of(demoSources),
                splitClasspath(inputs.getProperty(CLASSPATH_KEY)), outputDir, processorOptions);

        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(generatedSourcesByPath(result)).isEqualTo(readJavaTree(demoGenerated));
        assertThat(result.openApi()).as("OpenAPI spec").isNotNull();
    }

    @Test
    void everyGeneratedSourceCarriesTheGeneratedMarker(@TempDir Path sourceDir) throws IOException {
        GenerationResult result = generateFromSourceStrings(sourceDir);

        assertThat(result.filesOfKind(GeneratedFile.Kind.DTO)).isNotEmpty();
        assertThat(result.filesOfKind(GeneratedFile.Kind.MCP_TOOL)).isNotEmpty();
        assertThat(result.filesOfKind(GeneratedFile.Kind.REST_CONTROLLER)).isNotEmpty();
        assertThat(result.filesOfKind(GeneratedFile.Kind.OPENAPI)).hasSize(2);
        assertThat(result.filesOfKind(GeneratedFile.Kind.API_VERSION_PROPERTIES)).hasSize(1);
        assertThat(result.filesOfKind(GeneratedFile.Kind.DEPRECATION_MANIFEST)).hasSize(1);

        assertThat(result.files()).filteredOn(f -> f.kind().isSource())
                .allSatisfy(file -> assertThat(file.content()).contains(GENERATED_MARKER));
    }

    @Test
    void capturesCompilationErrorsAsDiagnostics(@TempDir Path sourceDir) throws IOException {
        Files.writeString(sourceDir.resolve("Broken.java"), """
                package test;
                public class Broken { this is not java }
                """, StandardCharsets.UTF_8);

        GenerationResult result = AtlasGenerator.generate(List.of(sourceDir), currentClasspath(), outputDir);

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.errors()).first()
                .satisfies(d -> assertThat(d.source()).endsWith("Broken.java"));
    }

    @Test
    void regeneratingIntoTheSameOutputDirectoryOverwritesResources(@TempDir Path sourceDir) throws IOException {
        generateFromSourceStrings(sourceDir);
        GenerationResult second = generateFromSourceStrings(sourceDir);

        assertThat(second.filesOfKind(GeneratedFile.Kind.OPENAPI)).hasSize(2);
        assertThat(second.filesOfKind(GeneratedFile.Kind.API_VERSION_PROPERTIES)).hasSize(1);
        assertThat(second.openApi()).isNotNull();
    }

    @Test
    void rejectsSourcePathThatDoesNotExist() {
        Path missing = outputDir.resolve("nowhere");

        assertThatThrownBy(() -> AtlasGenerator.generate(List.of(missing), currentClasspath(), outputDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void rejectsSourceSetWithoutJavaFiles(@TempDir Path sourceDir) {
        assertThatThrownBy(() -> AtlasGenerator.generate(List.of(sourceDir), currentClasspath(), outputDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No .java sources");
    }

    private GenerationResult generateFromSourceStrings(Path sourceDir) throws IOException {
        Path packageDir = Files.createDirectories(sourceDir.resolve("test"));
        Files.writeString(packageDir.resolve("Customer.java"), ENTITY_SOURCE, StandardCharsets.UTF_8);
        Files.writeString(packageDir.resolve("CustomerService.java"), SERVICE_SOURCE, StandardCharsets.UTF_8);

        GenerationResult result = AtlasGenerator.generate(List.of(sourceDir), currentClasspath(), outputDir);
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        return result;
    }

    private static Map<String, String> generatedSourcesByPath(GenerationResult result) {
        Map<String, String> byPath = new TreeMap<>();
        result.files().stream()
                .filter(file -> file.relativePath().endsWith(JAVA_SUFFIX))
                .forEach(file -> byPath.put(file.relativePath(), file.content()));
        return byPath;
    }

    private static Map<String, String> readJavaTree(Path root) throws IOException {
        Map<String, String> byPath = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                if (!file.getFileName().toString().endsWith(JAVA_SUFFIX)) {
                    continue;
                }
                String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
                byPath.put(relative, Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return byPath;
    }

    private static List<Path> currentClasspath() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(Path::of)
                .toList();
    }

    private static List<Path> splitClasspath(String classpath) {
        return Arrays.stream(classpath.split(File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .toList();
    }

    private static Properties load(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }

    private static Path pathProperty(String key) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static String stripPrefix(String name, String prefix) {
        return name.startsWith(prefix) ? name.substring(prefix.length()) : name;
    }

    private static String read(JavaFileObject file) {
        try {
            return file.getCharContent(true).toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file.getName(), e);
        }
    }
}
