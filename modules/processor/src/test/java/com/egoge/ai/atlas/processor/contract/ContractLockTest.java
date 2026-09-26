/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.egoge.ai.atlas.processor.contract.GateFixtures.Fixture;
import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.egoge.ai.atlas.processor.contract.GateFixtures.BASELINE;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.M;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.MAJOR;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.assertPasses;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.compile;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.fixture;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.irOf;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.singleError;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lock mode (FR-014): with {@code ai.atlas.contract.locked=true}, any difference between the
 * baseline and fresh IR documents fails the build until accepted, and so does a missing baseline.
 */
class ContractLockTest {

    private static final String LOCKED = "-A" + AgenticProcessor.OPT_CONTRACT_LOCKED + "=";

    @TempDir
    Path dir;
    private Path baseline;

    @BeforeEach
    void writeBaseline() throws IOException {
        baseline = GateFixtures.writeBaseline(dir);
    }

    @Test
    void lockedWithADescriptionChangeFailsListingTheElement() {
        Compilation compilation = gate(fixture().with("shop.Order", "@AgenticField(description = \"Total\")",
                "@AgenticField(description = \"Sum\")"), LOCKED + "true");

        assertThat(singleError(compilation)).contains("Lock mode (ai.atlas.contract.locked=true)")
                .contains(baseline.toString()).contains("at 1 element(s): field shop.Order#total")
                .contains("atlasAccept");
    }

    @Test
    void lockedCoversElementsInactiveAtThePublishedMajorAndTheDocument() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions(MAJOR + (M + 1), BASELINE + baseline, LOCKED + "TRUE").compile(fixture()
                        .with("shop.Order", "@AgenticField(description = \"Legacy\", removedInVersion = 2)",
                                "@AgenticField(description = \"Old\", removedInVersion = 2)").sources());

        // document: the major bump M → M + 1 changes the document's apiMajor, so lock mode requires atlasAccept
        assertThat(singleError(compilation)).contains("at 2 element(s): document, field shop.Order#legacy");
    }

    @Test
    void lockedWithAnIdenticalBaselinePasses() {
        assertPasses(gate(fixture(), LOCKED + "true"));
    }

    @Test
    void unlockedIgnoresCompatibleDifferences() {
        assertPasses(gate(fixture().with("shop.Order", "@AgenticField(description = \"Total\")",
                "@AgenticField(description = \"Sum\")"), LOCKED + "False"));
    }

    @Test
    void lockedWithNoBaselineFails() {
        Path missing = dir.resolve("missing/api.ir.json");
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions(MAJOR + M, BASELINE + missing, LOCKED + "true").compile(fixture().sources());
        assertThat(singleError(compilation)).contains("No contract baseline at " + missing)
                .contains("lock mode").contains("atlasAccept");

        Compilation unset = javac().withProcessors(new AgenticProcessor())
                .withOptions(MAJOR + M, LOCKED + "true").compile(fixture().sources());
        assertThat(singleError(unset)).contains("No contract baseline is configured")
                .contains("lock mode").contains("atlasAccept");
    }

    @Test
    void aBadLockedValueErrorsNamingOptionAndValue() {
        Compilation compilation = gate(fixture(), LOCKED + "yes");

        assertThat(singleError(compilation)).contains("ai.atlas.contract.locked must be 'true' or 'false'. Got: yes");
    }

    // ---------------------------------------------------------------- empty contract

    @Test
    void lockedEmptyContractFailsAgainstAnyNonEmptyBaselineDocument() throws IOException {
        // Nothing in this baseline is active at M, so the unlocked check passes
        Path future = Files.writeString(dir.resolve("future.json"), irOf(compile(fixture()
                .with("shop.Customer", "@AgenticField(description = \"Id\")",
                        "@AgenticField(description = \"Id\", sinceVersion = 3)")
                .only("shop.Customer").sources(), MAJOR + M)), StandardCharsets.UTF_8);
        assertThat(EmptyContract.check(future.toString(), false, "/api", M).failed()).isFalse();

        ContractGate.Outcome outcome = EmptyContract.check(future.toString(), true, "/api", M);

        assertThat(outcome.failed()).isTrue();
        assertThat(errors(outcome)).singleElement().asString().contains("Lock mode")
                .contains("entity shop.Customer, field shop.Customer#id");
    }

    @Test
    void lockedEmptyContractFailsAgainstAMissingBaseline() {
        ContractGate.Outcome outcome = EmptyContract.check(dir.resolve("none.json").toString(), true, "/api", M);

        assertThat(outcome.failed()).isTrue();
        assertThat(errors(outcome)).singleElement().asString().contains("No contract baseline at")
                .contains("lock mode");
    }

    @Test
    void lockedEmptyContractPassesAgainstAnIdenticalEmptyBaseline() throws IOException {
        Path empty = Files.writeString(dir.resolve("empty.json"), EmptyContract.json("/api", M),
                StandardCharsets.UTF_8);

        ContractGate.Outcome outcome = EmptyContract.check(empty.toString(), true, "/api", M);

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.findings()).isEmpty();
        assertThat(EmptyContract.check(empty.toString(), true, "/svc", M).failed()).isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private Compilation gate(Fixture fixture, String... options) {
        List<Object> all = new ArrayList<>(List.of(MAJOR + M, BASELINE + baseline));
        all.addAll(List.of(options));
        return javac().withProcessors(new AgenticProcessor()).withOptions(all).compile(fixture.sources());
    }

    private static List<String> errors(ContractGate.Outcome outcome) {
        return outcome.findings().stream().filter(f -> f.kind() == Diagnostic.Kind.ERROR)
                .map(ContractGate.Finding::message).toList();
    }
}
