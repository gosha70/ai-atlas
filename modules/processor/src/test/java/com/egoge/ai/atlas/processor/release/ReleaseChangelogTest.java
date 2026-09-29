/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.release;

import com.egoge.ai.atlas.processor.contract.ContractGate;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.EmptyContract;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.egoge.ai.atlas.processor.release.ReleaseFixtures.LEGACY;
import static com.egoge.ai.atlas.processor.release.ReleaseFixtures.NOTE;
import static com.egoge.ai.atlas.processor.release.ReleaseFixtures.ir;
import static com.egoge.ai.atlas.processor.release.ReleaseFixtures.release;
import static org.assertj.core.api.Assertions.assertThat;

/** {@link ReleaseChangelog}: one entry per difference, grouped into fixed sections, with no date. */
class ReleaseChangelogTest {

    private static final String DEPRECATED = LEGACY.formatted(", deprecatedSinceVersion = 1, removedInVersion = 2,"
            + " deprecatedMessage = \"Use id\"");

    @Test
    void aFirstReleaseListsEveryElementAsAdded() {
        assertThat(render(release("1.0.0", ir(1, "")))).isEqualTo("""
                ## 1.0.0 (API major 1)

                First release of this contract.

                ### Added

                - `field test.Order#id` (output, `java.lang.Long`)
                - `operation test.OrderService#find(java.lang.Long)` (input)
                """);
    }

    @Test
    void anAddedField() {
        assertThat(render(release("1.0.0", ir(1, "")), release("1.1.0", ir(1, NOTE)))).isEqualTo("""
                ## 1.1.0 (API major 1)

                Compared with 1.0.0 (API major 1).

                ### Added

                - `field test.Order#note` (output, `java.lang.String`)
                """);
    }

    @Test
    void aDeprecationWithItsRemovalMajorAndMessage() {
        assertThat(render(release("1.0.0", ir(1, LEGACY.formatted(""))), release("1.1.0", ir(1, DEPRECATED))))
                .isEqualTo("""
                        ## 1.1.0 (API major 1)

                        Compared with 1.0.0 (API major 1).

                        ### Deprecated

                        - `field test.Order#legacy`: deprecated since major 1, removed in major 2. Use id
                        """);
    }

    @Test
    void anOperationDeprecationNamesItsReplacement() {
        ContractIr deprecated = ReleaseFixtures.parse(ReleaseFixtures.irJson(1, "",
                ", apiDeprecatedSince = 1, apiReplacement = \"findV2\""));

        assertThat(render(release("1.0.0", ir(1, "")), release("1.1.0", deprecated))).contains("""
                ### Deprecated

                - `operation test.OrderService#find(java.lang.Long)`: deprecated since major 1. Replacement: findV2
                """);
    }

    @Test
    void aRemovalWithItsDeprecationEvidence() {
        assertThat(render(release("1.0.0", ir(1, DEPRECATED)), release("2.0.0", ir(2, DEPRECATED)))).isEqualTo("""
                ## 2.0.0 (API major 2)

                Compared with 1.0.0 (API major 1).

                ### Removed

                - `field test.Order#legacy` (output), deprecated since major 1 (first released deprecated in 1.0.0, 1 release(s))
                """);
    }

    @Test
    void aRemovalNeverDeprecatedSaysSo() {
        assertThat(render(release("1.0.0", ir(1, LEGACY.formatted(""))), release("2.0.0", ir(2, ""))))
                .contains("- `field test.Order#legacy` (output), never released as deprecated\n");
    }

    @Test
    void anotherBreakingChangeWithTheGatesReason() {
        String integer = LEGACY.formatted("").replace("String legacy", "Integer legacy")
                .replace("String getLegacy", "Integer getLegacy");

        assertThat(render(release("1.0.0", ir(1, LEGACY.formatted(""))), release("2.0.0", ir(2, integer))))
                .contains("""
                        ### Breaking

                        - `field test.Order#legacy`: javaType `java.lang.String` → `java.lang.Integer` (output). The field's schema in responses changes.
                        """);
    }

    @Test
    void aCompatibleChangeIsChanged() {
        String described = LEGACY.formatted("").replace("Legacy code", "The legacy code");

        assertThat(render(release("1.0.0", ir(1, LEGACY.formatted(""))), release("1.1.0", ir(1, described))))
                .contains("""
                        ### Changed

                        - `field test.Order#legacy`: description `Legacy code` → `The legacy code` (output).
                        """);
    }

    @Test
    void noChangesSaysSo() {
        assertThat(render(release("1.0.0", ir(1, "")), release("1.0.1", ir(1, "")))).isEqualTo("""
                ## 1.0.1 (API major 1)

                Compared with 1.0.0 (API major 1).

                No contract changes.
                """);
    }

    @Test
    void theAggregateListsSectionsInTheGivenOrder() {
        assertThat(ReleaseChangelog.aggregate(List.of("## 1.1.0 (API major 1)\n", "## 1.0.0 (API major 1)\n")))
                .isEqualTo("""
                        # Contract changelog

                        Generated from the release snapshots next to this file, newest first. Do not edit it: every release regenerates it.

                        ## 1.1.0 (API major 1)

                        ## 1.0.0 (API major 1)
                        """);
    }

    /** The last release's section, compared with the one before it. */
    private static String render(ReleasePolicy.Release... releases) {
        List<ReleasePolicy.Release> history = new ArrayList<>(List.of(releases));
        ReleasePolicy.Release current = history.get(history.size() - 1);
        ReleasePolicy.Release previous = history.size() > 1 ? history.get(history.size() - 2) : null;
        List<ContractGate.Difference> differences = ContractGate.compareReleases(previous != null ? previous.ir()
                : EmptyContract.document("/api", current.ir().apiMajor()), current.ir());
        ReleasePolicy.Result result = ReleasePolicy.check(history, differences, new ReleasePolicy.Policy(0, 0, false));
        return ReleaseChangelog.render(current.version(), current.ir(), previous, differences, result.evidence());
    }
}
