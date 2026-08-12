/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.driver;

import com.egoge.ai.atlas.annotations.AgenticEntity;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Sources and assertions shared by the {@link AtlasGenerator} test classes — the golden/parity
 * tests and the output-directory lifecycle tests both drive the same small annotated source set.
 */
final class DriverTestFixtures {

    static final String JAVA_SUFFIX = ".java";

    static final String ENTITY_SOURCE = """
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

    static final String SERVICE_SOURCE = """
            package test;

            import com.egoge.ai.atlas.annotations.AgenticExposed;
            import java.util.List;

            @AgenticExposed(description = "Look customers up", returnType = Customer.class)
            public class CustomerService {
                public Customer findById(Long id) { return null; }
                public List<Customer> findAll() { return List.of(); }
            }
            """;

    static final String EXTRA_ENTITY_SOURCE = """
            package test;

            import com.egoge.ai.atlas.annotations.AgenticEntity;
            import com.egoge.ai.atlas.annotations.AgenticField;

            @AgenticEntity(description = "A short-lived entity")
            public class Extra {
                @AgenticField(description = "Unique identifier")
                private Long id;

                public Long getId() { return id; }
            }
            """;

    /**
     * An entity whose collection is raw-typed, like the demo's {@code Customer.addresses}. The DTO
     * generated from it maps that collection through a raw {@code Stream}, so javac emits the
     * mandatory "uses unchecked or unsafe operations" note against the <em>generated</em> file —
     * the diagnostic that used to name the staging copy. The unannotated {@code ssn} field also
     * draws a PII note, which points at this caller-supplied source instead.
     */
    static final String RAW_COLLECTION_ENTITY_SOURCE = """
            package test;

            import com.egoge.ai.atlas.annotations.AgenticEntity;
            import com.egoge.ai.atlas.annotations.AgenticField;
            import java.util.ArrayList;
            import java.util.Collection;

            @AgenticEntity(description = "An order with raw-typed lines")
            public class Order {
                @AgenticField(description = "Unique identifier")
                private Long id;

                @AgenticField(description = "Order lines", type = OrderLine.class)
                private Collection lines = new ArrayList<>();

                private String ssn;

                public Long getId() { return id; }
                public Collection getLines() { return lines; }
                public String getSsn() { return ssn; }
            }
            """;

    static final String ORDER_LINE_ENTITY_SOURCE = """
            package test;

            import com.egoge.ai.atlas.annotations.AgenticEntity;
            import com.egoge.ai.atlas.annotations.AgenticField;

            @AgenticEntity(description = "A line on an order")
            public class OrderLine {
                @AgenticField(description = "Unique identifier")
                private Long id;

                public Long getId() { return id; }
            }
            """;

    private DriverTestFixtures() {
    }

    /** Writes {@link #RAW_COLLECTION_ENTITY_SOURCE} and its element type into {@code sourceDir}. */
    static void writeRawCollectionSources(Path sourceDir) throws IOException {
        Path packageDir = Files.createDirectories(sourceDir.resolve("test"));
        Files.writeString(packageDir.resolve("Order.java"), RAW_COLLECTION_ENTITY_SOURCE,
                StandardCharsets.UTF_8);
        Files.writeString(packageDir.resolve("OrderLine.java"), ORDER_LINE_ENTITY_SOURCE,
                StandardCharsets.UTF_8);
    }

    /** Writes {@link #ENTITY_SOURCE} and {@link #SERVICE_SOURCE} into {@code sourceDir}. */
    static void writeSampleSources(Path sourceDir) throws IOException {
        Path packageDir = Files.createDirectories(sourceDir.resolve("test"));
        Files.writeString(packageDir.resolve("Customer.java"), ENTITY_SOURCE, StandardCharsets.UTF_8);
        Files.writeString(packageDir.resolve("CustomerService.java"), SERVICE_SOURCE, StandardCharsets.UTF_8);
    }

    /** The test JVM's own classpath, which carries the Spring AI / Spring Web types (FR-005). */
    static List<Path> currentClasspath() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(Path::of)
                .toList();
    }

    /**
     * A classpath carrying the ai-atlas annotations and nothing else. The caller's own sources
     * compile against it, but the generated wrappers reference Spring AI / Spring Web, so
     * generation fails on the <em>generated</em> files — the classpath mistake a real caller makes.
     */
    static List<Path> annotationsOnlyClasspath() {
        try {
            return List.of(Path.of(AgenticEntity.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot locate the annotations classpath entry", e);
        }
    }

    /** The result's generated Java sources, keyed by their slash-separated relative path. */
    static Map<String, String> generatedSourcesByPath(GenerationResult result) {
        Map<String, String> byPath = new TreeMap<>();
        result.files().stream()
                .filter(file -> file.relativePath().endsWith(JAVA_SUFFIX))
                .forEach(file -> byPath.put(file.relativePath(), file.content()));
        return byPath;
    }

    /** The same shape as {@link #generatedSourcesByPath}, read straight off a directory tree. */
    static Map<String, String> readJavaTree(Path root) throws IOException {
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

    static List<String> dtoNames(GenerationResult result) {
        return result.filesOfKind(GeneratedFile.Kind.DTO).stream()
                .map(file -> file.path().getFileName().toString())
                .toList();
    }
}
