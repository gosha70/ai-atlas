/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.contract.ContractGate;
import com.egoge.ai.atlas.processor.contract.ContractGate.Classification;
import com.egoge.ai.atlas.processor.contract.ContractGate.Difference;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.ContractIr.Bound;
import com.egoge.ai.atlas.processor.contract.IrJson;
import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.egoge.ai.atlas.processor.CollectionsFixtures.FLAG_ON;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.compile;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.compileWithOrder;
import static com.egoge.ai.atlas.processor.CollectionsFixtures.resource;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Contract IR records each operation's effective result bound in {@code returns.bound}:
 * {@code NONE}/{@code NONE} with {@code ai.atlas.collections} off, whatever the sources; with it on,
 * the paging contract, envelope and declared bound the wrappers serve. The gate then sees an
 * envelope appear, and a bound or page-size ceiling change, as the IR version 4 rules classify them.
 */
class CollectionsIrTest {

    private static final String IR = ContractIr.RESOURCE_PATH;

    /** One unbounded list, one paged Page, one bounded list; {@code %s} is {@code top}'s declaration. */
    private static final String PLAIN_SRC = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import org.springframework.data.domain.*;
            import java.util.List;
            @AgenticExposed(description = "Plain", returnType = Order.class)
            public class Plain {
                @AgenticExposed(description = "Every order")
                public List<Order> every() { return Order.all(); }
                @AgenticExposed(description = "A page"%s)
                public Page<Order> paged(Pageable pageable) { return Page.empty(); }
                @AgenticExposed(description = "The top orders"%s)
                public List<Order> top() { return Order.all(); }
            }
            """;

    private static ContractIr ir(Compilation compilation) throws Exception {
        assertThat(compilation).succeeded();
        return IrJson.parse(resource(compilation, IR), "fresh");
    }

    private static Bound bound(ContractIr ir, String method) {
        return ir.operations().stream().filter(o -> o.method().equals(method)).findFirst().orElseThrow()
                .returns().bound();
    }

    private static Compilation plain(String pagedDeclaration, String topDeclaration, String... options) {
        return compileWithOrder("shop.Plain", PLAIN_SRC.formatted(pagedDeclaration, topDeclaration), options);
    }

    @Test
    void withTheFlagOffEveryOperationRecordsNoBound() throws Exception {
        ContractIr ir = ir(plain("", ""));

        assertThat(ir.operations()).allSatisfy(o -> assertThat(o.returns().bound()).isEqualTo(Bound.NONE));
    }

    @Test
    void withTheFlagOnEveryOperationRecordsItsEffectiveBound() throws Exception {
        ContractIr ir = ir(compile(FLAG_ON));

        assertThat(bound(ir, "byStatus")).isEqualTo(new Bound("PAGEABLE", "PAGE", "pageable", null, null));
        assertThat(bound(ir, "recent")).isEqualTo(new Bound("PAGEABLE", "SLICE", "pageable", null, 3));
        assertThat(bound(ir, "newest")).isEqualTo(new Bound("PAGEABLE", "NONE", "pageable", null, null));
        assertThat(bound(ir, "top")).isEqualTo(new Bound("DECLARED", "NONE", null, null, 2));
        assertThat(bound(ir, "search")).isEqualTo(new Bound("LIMIT", "NONE", "limit", "after", null));
        // A Page without a Pageable is served in its envelope, but nothing bounds it
        assertThat(bound(ir, "archived")).isEqualTo(new Bound("NONE", "PAGE", null, null, null));
        // A cursor alone bounds nothing
        assertThat(bound(ir, "after")).isEqualTo(Bound.NONE);
        assertThat(bound(ir, "list")).isEqualTo(Bound.NONE);
        assertThat(bound(ir, "find")).isEqualTo(Bound.NONE);
        // The IR records a CURSOR as optional by default
        assertThat(ir.operations().stream().filter(o -> o.method().equals("search")).findFirst().orElseThrow()
                .parameters().get(2).required()).isFalse();
    }

    @Test
    void theGateSeesTheEnvelopeTheFlagIntroducesAsABreakingOutputChange() throws Exception {
        List<Difference> differences = ContractGate.compare(ir(plain("", "")), ir(plain("", "", FLAG_ON)));

        assertThat(differences).filteredOn(d -> d.change().equals("returns.bound.envelope"))
                .singleElement().satisfies(d -> {
                    assertThat(d.path()).contains("paged");
                    assertThat(d.before()).isEqualTo("NONE");
                    assertThat(d.after()).isEqualTo("PAGE");
                    assertThat(d.classification()).isEqualTo(Classification.BREAKING);
                });
        assertThat(differences).filteredOn(d -> d.change().equals("returns.bound.style"))
                .singleElement().satisfies(d -> assertThat(d.classification()).isEqualTo(Classification.INFORMATIONAL));
    }

    @Test
    void theGateClassifiesDeclaredBoundsAndPageSizeCeilings() throws Exception {
        ContractIr none = ir(plain("", "", FLAG_ON));
        ContractIr bounded = ir(plain(", maxResults = 50", ", maxResults = 5", FLAG_ON));
        ContractIr looser = ir(plain(", maxResults = 100", ", maxResults = 10", FLAG_ON));

        // A bound appearing is compatible; a page-size ceiling appearing rejects larger pages
        assertThat(classification(ContractGate.compare(none, bounded), "maxResults")).isEqualTo(Classification.COMPATIBLE);
        assertThat(classification(ContractGate.compare(none, bounded), "pageSizeCeiling"))
                .isEqualTo(Classification.BREAKING);
        // Rising: clients may receive more (breaking); larger pages are accepted (compatible)
        assertThat(classification(ContractGate.compare(bounded, looser), "maxResults"))
                .isEqualTo(Classification.BREAKING);
        assertThat(classification(ContractGate.compare(bounded, looser), "pageSizeCeiling"))
                .isEqualTo(Classification.COMPATIBLE);
        // Disappearing
        assertThat(classification(ContractGate.compare(bounded, none), "maxResults")).isEqualTo(Classification.BREAKING);
        assertThat(classification(ContractGate.compare(bounded, none), "pageSizeCeiling"))
                .isEqualTo(Classification.COMPATIBLE);
    }

    @Test
    void aBaselineAcceptedWithTheFlagOffFailsTheBuildThatTurnsItOnForAPage(@TempDir Path dir) throws Exception {
        Path baseline = dir.resolve("api.ir.json");
        Files.writeString(baseline, resource(plain("", ""), IR), StandardCharsets.UTF_8);

        Compilation compilation = plain("", "", FLAG_ON, "-Aai.atlas.contract.baseline=" + baseline);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("returns.bound.envelope");
    }

    private static Classification classification(List<Difference> differences, String key) {
        return differences.stream().filter(d -> d.change().equals("returns.bound." + key)).findFirst().orElseThrow()
                .classification();
    }
}
