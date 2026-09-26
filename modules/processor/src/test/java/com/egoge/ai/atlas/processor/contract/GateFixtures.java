/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/** The gate tests' fixture, published at {@link #M}, and the compilations they run. */
final class GateFixtures {

    static final int M = 2;
    static final String MAJOR = "-A" + AgenticProcessor.OPT_API_MAJOR + "=";
    static final String BASELINE = "-A" + AgenticProcessor.OPT_CONTRACT_BASELINE + "=";

    /** The published fixture: entities with every field kind, and operations on every channel. */
    static final Map<String, String> FIXTURE = Map.of(
            "shop.Status", """
                    package shop;
                    public enum Status { OPEN, CLOSED }
                    """,
            "shop.Priority", """
                    package shop;
                    public enum Priority { LOW, HIGH }
                    """,
            "shop.Customer", """
                    package shop;
                    import com.egoge.ai.atlas.annotations.AgenticEntity;
                    import com.egoge.ai.atlas.annotations.AgenticField;
                    @AgenticEntity(description = "A customer")
                    public class Customer {
                        @AgenticField(description = "Id") private Long id;
                        public Long getId() { return id; }
                    }
                    """,
            "shop.Order", """
                    package shop;
                    import com.egoge.ai.atlas.annotations.AgenticEntity;
                    import com.egoge.ai.atlas.annotations.AgenticField;
                    import java.util.List;
                    @AgenticEntity(description = "An order")
                    public class Order {
                        @AgenticField(description = "Id") private Long id;
                        @AgenticField(description = "Total") private Long total;
                        @AgenticField(description = "Status") private Status status;
                        @AgenticField(description = "Size", allowedValues = {"S", "M"}) private String size;
                        @AgenticField(description = "Legacy", removedInVersion = 2) private String legacy;
                        @AgenticField(description = "Related", type = Customer.class) private List<?> related;
                        public Long getId() { return id; }
                        public Long getTotal() { return total; }
                        public Status getStatus() { return status; }
                        public String getSize() { return size; }
                        public String getLegacy() { return legacy; }
                        public List<?> getRelated() { return related; }
                    }
                    """,
            "shop.OrderService", """
                    package shop;
                    import com.egoge.ai.atlas.annotations.AgenticExposed;
                    import java.util.List;
                    @AgenticExposed(description = "Orders")
                    public class OrderService {
                        public String find() { return null; }
                        @AgenticExposed(description = "Recent orders", returnType = Order.class)
                        public List<?> recent() { return null; }
                        @AgenticExposed(description = "Count by priority")
                        public long count(Priority priority) { return 0; }
                        @AgenticExposed(description = "Label an order", toolName = "labelOrder")
                        public String label(Long id) { return null; }
                    }
                    """,
            "shop.ReportService", """
                    package shop;
                    import com.egoge.ai.atlas.annotations.AgenticExposed;
                    import java.util.List;
                    @AgenticExposed(description = "Reports", returnType = Order.class)
                    public class ReportService {
                        public List<?> all() { return null; }
                    }
                    """);

    private GateFixtures() {
    }

    /** A mutable copy of {@link #FIXTURE}. */
    static Fixture fixture() {
        return new Fixture(new LinkedHashMap<>(FIXTURE));
    }

    /** Sources as a map of qualified class name to text. */
    record Fixture(Map<String, String> files) {

        /** Replaces {@code from}, which must occur, with {@code to} in {@code file}. */
        Fixture with(String file, String from, String to) {
            String source = files.get(file);
            assertThat(source).as("fixture " + file).contains(from);
            files.put(file, source.replace(from, to));
            return this;
        }

        Fixture add(String file, String source) {
            files.put(file, source);
            return this;
        }

        /** Keeps only {@code file}. */
        Fixture only(String file) {
            files.keySet().removeIf(f -> !f.equals(file));
            return this;
        }

        List<JavaFileObject> sources() {
            List<JavaFileObject> sources = new ArrayList<>();
            files.forEach((name, text) -> sources.add(JavaFileObjects.forSourceString(name, text)));
            return sources;
        }
    }

    /** Compiles {@link #FIXTURE} at {@link #M} and writes its IR to {@code dir} as the baseline. */
    static Path writeBaseline(Path dir) throws IOException {
        return Files.writeString(dir.resolve("api.ir.json"), irOf(compile(fixture().sources(), MAJOR + M)),
                StandardCharsets.UTF_8);
    }

    /** Compiles {@code sources}, which must succeed. */
    static Compilation compile(List<JavaFileObject> sources, String... options) {
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).withOptions((Object[]) options)
                .compile(sources);
        assertThat(compilation.status()).as(compilation.diagnostics().toString())
                .isEqualTo(Compilation.Status.SUCCESS);
        return compilation;
    }

    static void assertPasses(Compilation compilation) {
        assertThat(compilation.status()).as(compilation.diagnostics().toString())
                .isEqualTo(Compilation.Status.SUCCESS);
    }

    /** The error messages of a compilation, which must have failed. */
    static List<String> errors(Compilation compilation) {
        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        return compilation.errors().stream().map(d -> d.getMessage(null)).toList();
    }

    /** The one error message of a compilation, which must have failed. */
    static String singleError(Compilation compilation) {
        List<String> errors = errors(compilation);
        assertThat(errors).hasSize(1);
        return errors.get(0);
    }

    /** Every error is reported on the declaration element, which still exists. */
    static void assertOnElement(Compilation compilation) {
        assertThat(compilation.errors()).allSatisfy(d -> assertThat(d.getSource()).isNotNull());
    }

    static List<String> notes(Compilation compilation, String containing) {
        return compilation.notes().stream().map(d -> d.getMessage(null)).filter(m -> m.contains(containing)).toList();
    }

    static String irOf(Compilation compilation) {
        return generated(compilation, ContractIr.RESOURCE_PATH);
    }

    static String generated(Compilation compilation, String path) {
        Optional<JavaFileObject> file = compilation.generatedFile(StandardLocation.CLASS_OUTPUT, path);
        assertThat(file).as("generated " + path).isPresent();
        try (var in = file.get().openInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
