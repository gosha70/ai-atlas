/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.release;

import com.egoge.ai.atlas.processor.contract.ContractGate;
import com.egoge.ai.atlas.processor.contract.ContractGate.Classification;
import com.egoge.ai.atlas.processor.contract.ContractGate.Difference;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.EmptyContract;
import com.egoge.ai.atlas.processor.contract.ReleaseComparison;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.egoge.ai.atlas.processor.release.ReleaseFixtures.LEGACY;
import static com.egoge.ai.atlas.processor.release.ReleaseFixtures.NOTE;
import static com.egoge.ai.atlas.processor.release.ReleaseFixtures.ir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link ReleaseComparison#compare}: the gate's own comparison, over what each release published
 * at its own major.
 */
class ReleaseComparisonTest {

    private static final String DECLARED_REMOVAL = LEGACY.formatted(", deprecatedSinceVersion = 1,"
            + " removedInVersion = 2, deprecatedMessage = \"Use id\"");

    @Test
    void aDeclaredRemovalAcrossAMajorIsARemoval() {
        ContractIr previous = ir(1, DECLARED_REMOVAL);
        ContractIr current = ir(2, DECLARED_REMOVAL);

        // At the previous major the declared removal is invisible, by design of the gate
        assertThat(ContractGate.compare(previous, current)).isEmpty();
        List<Difference> differences = ReleaseComparison.compare(previous, current);

        assertThat(differences).extracting(Difference::path, Difference::change, Difference::classification)
                .containsExactly(tuple("field test.Order#legacy", "removed", Classification.BREAKING));
        assertThat(differences.get(0).reason()).isEqualTo("Responses no longer carry the field");
    }

    @Test
    void identicalReleasesDoNotDiffer() {
        assertThat(ReleaseComparison.compare(ir(1, NOTE), ir(1, NOTE))).isEmpty();
    }

    @Test
    void aFirstReleaseAddsEveryPublishedElement() {
        ContractIr current = ir(1, NOTE);

        List<Difference> differences = ReleaseComparison.compare(EmptyContract.document("/api", 1), current);

        assertThat(differences).extracting(Difference::path, Difference::change, Difference::classification)
                .containsExactly(
                        tuple("field test.Order#id", "added", Classification.COMPATIBLE),
                        tuple("field test.Order#note", "added", Classification.COMPATIBLE),
                        tuple("operation test.OrderService#find(java.lang.Long)", "added", Classification.COMPATIBLE));
    }

    @Test
    void aDeprecationIsACompatibleLifecycleChangeOfThePublishedSurface() {
        ContractIr previous = ir(1, LEGACY.formatted(""));
        ContractIr current = ir(1, DECLARED_REMOVAL);

        List<Difference> differences = ReleaseComparison.compare(previous, current);

        assertThat(differences).extracting(Difference::path, Difference::change, Difference::classification)
                .containsExactly(tuple("field test.Order#legacy", "lifecycle", Classification.COMPATIBLE));
    }

    @Test
    void aDeprecationDeclaredForALaterMajorIsNotPublished() {
        ContractIr previous = ir(1, LEGACY.formatted(""));
        ContractIr current = ir(1, LEGACY.formatted(", deprecatedSinceVersion = 2"));

        assertThat(ReleaseComparison.compare(previous, current)).isEmpty();
    }

    @Test
    void anElementNotYetActiveIsNotPublished() {
        ContractIr previous = ir(1, "");
        ContractIr later = ir(1, LEGACY.formatted(", sinceVersion = 2"));

        assertThat(ReleaseComparison.compare(previous, later)).isEmpty();
        assertThat(ReleaseComparison.compare(later, ir(2, LEGACY.formatted(", sinceVersion = 2"))))
                .extracting(Difference::path, Difference::change)
                .containsExactly(tuple("field test.Order#legacy", "added"));
    }

    @Test
    void theReasonsNameThePreviousReleasesMajor() {
        ContractIr previous = ir(1, LEGACY.formatted(""));
        ContractIr current = ir(2, "");

        List<Difference> differences = ReleaseComparison.compare(previous, current);

        assertThat(differences).singleElement().satisfies(d -> assertThat(d.remedy()).contains("removedInVersion = 2"));
    }

    @Test
    void aChannelLossOnAReachableEntityIsBreaking() {
        ContractIr previous = ReleaseFixtures.parse(ReleaseFixtures.irJson(1, NOTE, ""));
        ContractIr current = ReleaseFixtures.parse(ReleaseFixtures.irJson(1,
                NOTE.replace("description = \"A note\"", "description = \"A note\", channels = AgenticExposed.Channel.API"),
                "", ReleaseFixtures.PROJECTIONS));

        List<Difference> differences = ReleaseComparison.compare(previous, current);

        assertThat(differences).filteredOn(Difference::breaking)
                .extracting(Difference::path, Difference::change, Difference::before, Difference::after)
                .containsExactly(tuple("field test.Order#note", "channels.AI", "[AI, API]", "[API]"));
    }
}
