/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.egoge.ai.atlas.processor.contract.GateFixtures.M;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.MAJOR;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.compile;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.fixture;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.irOf;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The empty contract (FR-008, ADR-8): javac does not run the processor for a compilation with no
 * ai-atlas annotation, so {@link EmptyContract} checks it against the baseline with the gate's rules.
 */
class EmptyContractGateTest {

    @TempDir
    Path dir;

    @Test
    void emptyContractAgainstABaselineWithActiveElementsFailsReportingEachRemoval() throws IOException {
        Path baseline = GateFixtures.writeBaseline(dir);

        ContractGate.Outcome outcome = EmptyContract.check(baseline.toString(), "/api", M);

        assertThat(outcome.failed()).isTrue();
        List<String> errors = outcome.findings().stream().filter(f -> f.kind() == Diagnostic.Kind.ERROR)
                .map(ContractGate.Finding::message).toList();
        assertThat(errors.get(0)).contains("declares no @AgenticEntity or @AgenticExposed")
                .contains(baseline.toString()).contains("at major 2").contains("atlasAccept");
        assertThat(errors.subList(1, errors.size())).allSatisfy(e -> assertThat(e)
                        .startsWith("[ai-atlas] Breaking contract change to ").contains(": removed ")
                        .contains("breaks clients of major 2").contains("atlasAccept"))
                .anySatisfy(e -> assertThat(e).contains("field shop.Order#total: removed"))
                .anySatisfy(e -> assertThat(e).contains("field shop.Customer#id: removed"))
                .anySatisfy(e -> assertThat(e).contains("operation shop.OrderService#find(): removed"))
                .anySatisfy(e -> assertThat(e).contains("operation shop.ReportService#all(): removed"))
                .noneSatisfy(e -> assertThat(e).contains("field shop.Order#legacy"));
        assertThat(outcome.differences()).filteredOn(ContractGate.Difference::breaking).hasSize(errors.size() - 1);
    }

    @Test
    void emptyContractAgainstABaselineWithNothingActiveAtItsMajorPasses() throws IOException {
        Path future = Files.writeString(dir.resolve("future.json"), irOf(compile(fixture()
                .with("shop.Customer", "@AgenticField(description = \"Id\")",
                        "@AgenticField(description = \"Id\", sinceVersion = 3)")
                .only("shop.Customer").add("shop.Later", """
                        package shop;
                        import com.egoge.ai.atlas.annotations.AgenticExposed;
                        @AgenticExposed(description = "Later", apiSince = 3)
                        public class Later {
                            public String soon() { return null; }
                        }
                        """).sources(), MAJOR + M)), StandardCharsets.UTF_8);

        ContractGate.Outcome outcome = EmptyContract.check(future.toString(), "/api", M);

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.compared()).isTrue();
        assertThat(outcome.findings()).isEmpty();
    }

    @Test
    void emptyContractDocumentIsCanonicalAndStable() throws Exception {
        String first = EmptyContract.json("/api", 3);

        assertThat(EmptyContract.json("/api", 3).getBytes(StandardCharsets.UTF_8))
                .isEqualTo(first.getBytes(StandardCharsets.UTF_8));
        ContractIr parsed = IrJson.parse(first, "empty");
        assertThat(parsed).isEqualTo(EmptyContract.document("/api", 3));
        assertThat(IrJson.write(parsed)).isEqualTo(first);
    }

    @Test
    void processorSupportsOnlyTheTwoAiAtlasAnnotations() {
        assertThat(new AgenticProcessor().getSupportedAnnotationTypes()).containsExactlyInAnyOrder(
                "com.egoge.ai.atlas.annotations.AgenticEntity", "com.egoge.ai.atlas.annotations.AgenticExposed");
    }

    @Test
    void noBaselineAndNoAnnotationProduceNoFileAndNoDiagnostic() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(
                JavaFileObjects.forSourceString("plain.Plain", """
                        package plain;
                        public class Plain {
                            @Override public String toString() { return "plain"; }
                        }
                        """));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(compilation.diagnostics()).isEmpty();
        assertThat(compilation.generatedFiles())
                .allSatisfy(f -> assertThat(f.getKind()).isEqualTo(JavaFileObject.Kind.CLASS));
    }
}
