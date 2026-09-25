/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.egoge.ai.atlas.processor.driver.AtlasGenerator;
import com.egoge.ai.atlas.processor.driver.GenerationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The golden snapshot of every generated source and resource, captured from the generators before
 * they were rewired to the Contract IR (FR-007, ADR-3). The rewire changes where the models come
 * from, not what is generated, so the output must stay byte-identical, file for file.
 *
 * <p>Inputs live under {@code golden/ir-rewire/fixtures/<fixture>/}; expected output under
 * {@code golden/ir-rewire/expected/<case>/}, keyed by output location ({@code SOURCE_OUTPUT/…},
 * {@code CLASS_OUTPUT/…}). The new {@code META-INF/ai-atlas/} files are not part of the snapshot.
 *
 * <p>Recapture only on a deliberate output change: {@code ./gradlew :modules:processor:test
 * --tests '*IrRewireGoldenTest' -Pai.atlas.golden.capture=true}.
 */
class IrRewireGoldenTest {

    private static final Path GOLDEN_ROOT = Path.of("src/test/resources/golden/ir-rewire");
    private static final Path FIXTURES = GOLDEN_ROOT.resolve("fixtures");
    private static final Path EXPECTED = GOLDEN_ROOT.resolve("expected");
    private static final String CAPTURE_PROPERTY = "ai.atlas.golden.capture";
    private static final String SOURCE_OUTPUT = "SOURCE_OUTPUT/";
    private static final String CLASS_OUTPUT = "CLASS_OUTPUT/";
    /** The files this feature adds; everything else the processor writes is in the snapshot. */
    private static final Set<String> IR_FILES = Set.of(
            CLASS_OUTPUT + ContractIr.RESOURCE_PATH,
            CLASS_OUTPUT + "META-INF/ai-atlas/contract-diff.json");
    private static final String JAVA_SUFFIX = ".java";
    private static final String CLASS_SUFFIX = ".class";
    private static final String DEMO_CASE = "demo";
    private static final String NO_ANNOTATION_CASE = "no-annotation";

    private static final String DEMO_SOURCES_PROPERTY = "ai.atlas.demo.sources";
    private static final String DEMO_INPUTS_PROPERTY = "ai.atlas.demo.generationInputs";
    private static final String OPTION_PREFIX = "option.";
    private static final String CLASSPATH_KEY = "classpath";

    /**
     * One compilation of a fixture at the given {@code -A} options. The two #25 fixtures that fail
     * with a mapping error still generate files before the error, and those are snapshotted too.
     */
    record GoldenCase(String name, String fixture, Map<String, String> options, boolean expectSuccess) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<GoldenCase> fixtureCases() {
        return Stream.of(
                new GoldenCase("lifecycle-v1", "lifecycle", Map.of(AgenticProcessor.OPT_API_MAJOR, "1"), true),
                new GoldenCase("lifecycle-v2", "lifecycle", Map.of(AgenticProcessor.OPT_API_MAJOR, "2"), true),
                new GoldenCase("lifecycle-v3", "lifecycle", Map.of(AgenticProcessor.OPT_API_MAJOR, "3",
                        AgenticProcessor.OPT_API_BASE_PATH, "/shop-api"), true),
                new GoldenCase("shared-find", "shared-find", Map.of(), true),
                new GoldenCase("rest-openapi-catalog", "rest-openapi-catalog", Map.of(), true),
                new GoldenCase("rest-openapi-overloaded", "rest-openapi-overloaded", Map.of(), true),
                new GoldenCase("rest-openapi-taken-id", "rest-openapi-taken-id", Map.of(), true),
                new GoldenCase("rest-openapi-post-overloads", "rest-openapi-post-overloads", Map.of(), false),
                new GoldenCase("rest-openapi-same-name", "rest-openapi-same-name", Map.of(), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureCases")
    void fixtureOutputMatchesTheGoldenSnapshot(GoldenCase goldenCase) throws IOException {
        Map<String, String> actual = compileFixture(goldenCase.fixture(), goldenCase.options(),
                goldenCase.expectSuccess());

        assertThat(actual).as("fixture %s generates output", goldenCase.name()).isNotEmpty();
        verifyOrCapture(goldenCase.name(), actual);
    }

    @Test
    void compilationWithoutAiAtlasAnnotationsGeneratesNothing() throws IOException {
        Map<String, String> actual = compileFixture(NO_ANNOTATION_CASE, Map.of(), true);

        assertThat(actual).isEmpty();
        assertThat(EXPECTED.resolve(NO_ANNOTATION_CASE)).doesNotExist();
    }

    @Test
    void demoOutputMatchesTheGoldenSnapshot(@TempDir Path outputDir) throws IOException {
        Path demoSources = pathProperty(DEMO_SOURCES_PROPERTY);
        Path generationInputs = pathProperty(DEMO_INPUTS_PROPERTY);
        assumeTrue(demoSources != null && generationInputs != null,
                "demo inputs are wired by Gradle; skipping standalone run");
        assumeTrue(Files.isRegularFile(generationInputs), "demo has not been compiled yet; skipping");

        Properties inputs = new Properties();
        try (InputStream in = Files.newInputStream(generationInputs)) {
            inputs.load(in);
        }
        Map<String, String> options = new TreeMap<>();
        for (String key : inputs.stringPropertyNames()) {
            if (key.startsWith(OPTION_PREFIX)) {
                options.put(key.substring(OPTION_PREFIX.length()), inputs.getProperty(key));
            }
        }
        List<Path> classpath = Arrays.stream(inputs.getProperty(CLASSPATH_KEY).split(File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .toList();

        GenerationResult result = AtlasGenerator.generate(List.of(demoSources), classpath, outputDir, options);
        assertThat(result.errors()).isEmpty();

        Map<String, String> actual = new TreeMap<>();
        result.files().forEach(file -> put(actual,
                (file.relativePath().endsWith(JAVA_SUFFIX) ? SOURCE_OUTPUT : CLASS_OUTPUT)
                        + file.relativePath(), file.content()));
        assertThat(actual).isNotEmpty();
        verifyOrCapture(DEMO_CASE, actual);
    }

    /**
     * Every generated source and non-class resource of compiling the fixture, keyed by location.
     * Runs javac directly into scratch directories, so that a compilation failing with an ai-atlas
     * error still yields what the generators wrote before it.
     */
    private static Map<String, String> compileFixture(String fixture, Map<String, String> options,
                                                      boolean expectSuccess) throws IOException {
        Path scratch = Files.createTempDirectory("ir-rewire-golden");
        try {
            Path sourceOut = Files.createDirectories(scratch.resolve(SOURCE_OUTPUT));
            Path classOut = Files.createDirectories(scratch.resolve(CLASS_OUTPUT));
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            List<String> compilerOptions = new ArrayList<>(List.of("-encoding", "UTF-8"));
            options.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> compilerOptions.add("-A" + e.getKey() + "=" + e.getValue()));
            boolean success;
            try (StandardJavaFileManager fileManager =
                         compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
                fileManager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(sourceOut));
                fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classOut));
                fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, testClasspath());
                JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics,
                        compilerOptions, null,
                        fileManager.getJavaFileObjectsFromPaths(listFiles(FIXTURES.resolve(fixture))));
                task.setProcessors(List.of(new AgenticProcessor()));
                success = Boolean.TRUE.equals(task.call());
            }
            assertThat(success).as("compilation of %s succeeds; diagnostics: %s", fixture,
                    diagnostics.getDiagnostics()).isEqualTo(expectSuccess);

            Map<String, String> generated = new TreeMap<>();
            for (Path file : listFiles(scratch)) {
                String key = relative(scratch, file);
                if (!key.endsWith(CLASS_SUFFIX)) {
                    put(generated, key, Files.readString(file, StandardCharsets.UTF_8));
                }
            }
            return generated;
        } finally {
            deleteRecursively(scratch);
        }
    }

    private static List<Path> testClasspath() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .toList();
    }

    private static void put(Map<String, String> files, String key, String content) {
        if (!IR_FILES.contains(key)) {
            files.put(key, content);
        }
    }

    private static void verifyOrCapture(String caseName, Map<String, String> actual) throws IOException {
        Path expectedRoot = EXPECTED.resolve(caseName);
        if (Boolean.parseBoolean(System.getProperty(CAPTURE_PROPERTY))) {
            deleteRecursively(expectedRoot);
            for (var entry : actual.entrySet()) {
                Path target = expectedRoot.resolve(entry.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
            }
            return;
        }
        assertThat(expectedRoot).as("golden snapshot for %s", caseName).isDirectory();

        Map<String, String> expected = new TreeMap<>();
        for (Path file : listFiles(expectedRoot)) {
            expected.put(relative(expectedRoot, file), Files.readString(file, StandardCharsets.UTF_8));
        }
        assertThat(actual.keySet()).as("generated file set of %s", caseName)
                .containsExactlyElementsOf(expected.keySet());
        for (var entry : expected.entrySet()) {
            assertThat(actual.get(entry.getKey()).getBytes(StandardCharsets.UTF_8))
                    .as("%s: %s", caseName, entry.getKey())
                    .isEqualTo(entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static List<Path> listFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static Path pathProperty(String key) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? null : Path.of(value);
    }
}
