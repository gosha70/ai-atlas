/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.cli;

import com.egoge.ai.atlas.annotations.AgenticEntity;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Sources and helpers shared by the CLI test classes — the in-process behaviour tests and the
 * functional test that drives the packaged {@code atlas.jar} generate from the same source set, so
 * the two can be compared against each other.
 */
final class CliTestFixtures {

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

    /** A perfectly valid source file that carries no ai-atlas annotation — nothing to generate. */
    static final String PLAIN_SOURCE = """
            package plain;

            public class Plain {
                public String hello() { return "hi"; }
            }
            """;

    /**
     * An entity whose collection is raw-typed. The DTO generated from it maps that collection
     * through a raw {@code Stream}, so javac emits its mandatory "uses unchecked or unsafe
     * operations" note naming the <em>generated</em> file — the diagnostic that used to report the
     * driver's staging path.
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

                @AgenticField(description = "Order lines", type = Customer.class)
                private Collection lines = new ArrayList<>();

                public Long getId() { return id; }
                public Collection getLines() { return lines; }
            }
            """;

    private CliTestFixtures() {
    }

    /** Adds {@link #RAW_COLLECTION_ENTITY_SOURCE} to a source root {@link #writeSampleSources} filled. */
    static void writeRawCollectionSource(Path sourceDir) throws IOException {
        Files.writeString(sourceDir.resolve("test").resolve("Order.java"),
                RAW_COLLECTION_ENTITY_SOURCE, StandardCharsets.UTF_8);
    }

    /** Writes {@link #ENTITY_SOURCE} and {@link #SERVICE_SOURCE} into {@code sourceDir}. */
    static void writeSampleSources(Path sourceDir) throws IOException {
        Path packageDir = Files.createDirectories(sourceDir.resolve("test"));
        Files.writeString(packageDir.resolve("Customer.java"), ENTITY_SOURCE, StandardCharsets.UTF_8);
        Files.writeString(packageDir.resolve("CustomerService.java"), SERVICE_SOURCE,
                StandardCharsets.UTF_8);
    }

    /** Writes {@link #PLAIN_SOURCE} into {@code sourceDir} and returns that root. */
    static Path writePlainSource(Path sourceDir) throws IOException {
        Path packageDir = Files.createDirectories(sourceDir.resolve("plain"));
        Files.writeString(packageDir.resolve("Plain.java"), PLAIN_SOURCE, StandardCharsets.UTF_8);
        return sourceDir;
    }

    /** The test JVM's classpath, which carries the Spring AI / Spring Web types (FR-005). */
    static String testClasspath() {
        return System.getProperty("java.class.path");
    }

    /**
     * A {@code --classpath} carrying the ai-atlas annotations and nothing else. The caller's own
     * sources compile against it, but the generated wrappers reference Spring AI / Spring Web, so
     * generation fails on the <em>generated</em> files — the classpath mistake a real caller makes.
     */
    static String annotationsOnlyClasspath() {
        try {
            return Path.of(AgenticEntity.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot locate the annotations classpath entry", e);
        }
    }

    /**
     * Every regular file under {@code root}, keyed by its relative path — a missing root reads as
     * empty, so a run that published nothing is still comparable with one that did.
     */
    static Map<String, String> readTree(Path root) throws IOException {
        Map<String, String> byPath = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            return byPath;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                byPath.put(root.relativize(file).toString(),
                        Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return byPath;
    }
}
