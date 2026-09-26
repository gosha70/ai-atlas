/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.egoge.ai.atlas.processor.contract.GateFixtures.BASELINE;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.M;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.MAJOR;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.assertPasses;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.compile;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.fixture;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.irOf;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.notes;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/** How the gate treats the baseline path and failures reading it (FR-008). */
class ContractBaselineTest {

    @TempDir
    Path dir;

    @Test
    void aBaselinePathThatIsNotARegularFileIsTreatedAsMissing() throws IOException {
        Path directory = Files.createDirectories(dir.resolve("a-directory"));

        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions(MAJOR + M, BASELINE + directory).compile(fixture().sources());

        assertPasses(compilation);
        assertThat(notes(compilation, "contract baseline")).singleElement().asString()
                .contains("No contract baseline at " + directory).contains("atlasAccept");
    }

    @Test
    void anInternalFailureComparingTheFreshIrIsNotBlamedOnTheBaseline() throws Exception {
        Path baseline = GateFixtures.writeBaseline(dir);
        String ir = irOf(compile(fixture().sources(), MAJOR + M));
        // shop.Customer#id, active at M, is the first Long in the document
        String broken = ir.replaceFirst("\"javaType\": \"java.lang.Long\"", "\"javaType\": \"<any>\"");
        assertThat(broken).isNotEqualTo(ir);

        ContractGate.Outcome fresh = ContractGate.check(baseline.toString(), IrJson.parse(broken, "fresh"));
        assertThat(fresh.failed()).isTrue();
        assertThat(fresh.findings()).singleElement().satisfies(f -> assertThat(f.message())
                .contains("Internal error comparing the compilation's Contract IR").doesNotContain("not valid"));

        Files.writeString(baseline, broken, StandardCharsets.UTF_8);
        ContractGate.Outcome badBaseline = ContractGate.check(baseline.toString(), IrJson.parse(ir, "fresh"));
        assertThat(badBaseline.findings()).singleElement().satisfies(f -> assertThat(f.message())
                .contains("Contract baseline " + baseline + " is not valid Contract IR JSON"));
    }
}
