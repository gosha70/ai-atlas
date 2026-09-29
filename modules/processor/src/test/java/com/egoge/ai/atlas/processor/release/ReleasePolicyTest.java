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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ReleasePolicy}: removals need released deprecation, and breaking changes need a new major. */
class ReleasePolicyTest {

    private static final String PLAIN = LEGACY.formatted("");
    private static final String DEPRECATED = LEGACY.formatted(", deprecatedSinceVersion = 1, removedInVersion = 2");
    private static final String FIELD = "field test.Order#legacy";
    private static final String FIND = "operation test.OrderService#find(java.lang.Long)";

    @Test
    void aFirstReleasePasses() {
        ContractIr current = ir(1, NOTE);

        ReleasePolicy.Result result = check(ReleasePolicy.Policy.DEFAULT, release("1.0.0", current));

        assertThat(result.passed()).isTrue();
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void aRemovalNeverReleasedDeprecatedFails() {
        ReleasePolicy.Result result = check(ReleasePolicy.Policy.DEFAULT,
                release("1.0.0", ir(1, PLAIN)), release("1.1.0", ir(1, "")));

        assertThat(result.violations()).singleElement().satisfies(v -> {
            assertThat(v.path()).isEqualTo(FIELD);
            assertThat(v.change()).isEqualTo("removed");
            assertThat(v.evidence()).isEqualTo("removed in API major 1, but never released as deprecated");
            assertThat(v.remedy()).contains("released deprecated in at least 1 release(s)",
                    "removed at least 1 major(s) after its deprecation major",
                    "declare @AgenticField(deprecatedSinceVersion = N)", ReleasePolicy.CONFIGURATION);
            assertThat(v.message()).startsWith(FIELD + " (removed): removed in API major 1, but never released");
        });
        assertThat(result.evidence().get(FIELD)).isEqualTo(new ReleasePolicy.Evidence(0, 0, null));
    }

    @Test
    void aRemovalInTheMajorOfItsDeprecationFailsTheDefaultPolicy() {
        ReleasePolicy.Result result = check(ReleasePolicy.Policy.DEFAULT,
                release("1.0.0", ir(1, DEPRECATED)), release("1.1.0", ir(1, "")));

        assertThat(result.violations()).singleElement().extracting(ReleasePolicy.Violation::evidence)
                .isEqualTo("removed in API major 1, deprecated since major 1 and released deprecated in 1 release(s)"
                        + " from 1.0.0");
    }

    @Test
    void aRemovalAMajorAfterAReleasedDeprecationPasses() {
        ReleasePolicy.Result result = check(ReleasePolicy.Policy.DEFAULT,
                release("1.0.0", ir(1, PLAIN)), release("1.1.0", ir(1, DEPRECATED)),
                release("2.0.0", ir(2, DEPRECATED)));

        assertThat(result.passed()).isTrue();
        assertThat(result.evidence().get(FIELD))
                .isEqualTo(new ReleasePolicy.Evidence(1, 1, ReleaseVersion.parse("1.1.0")));
    }

    @Test
    void minReleasesCountsTheReleasesThatPublishedTheDeprecation() {
        ReleasePolicy.Policy twoReleases = new ReleasePolicy.Policy(2, 1, true);
        ReleasePolicy.Release deprecated = release("1.0.0", ir(1, DEPRECATED));

        assertThat(check(twoReleases, deprecated, release("2.0.0", ir(2, DEPRECATED))).passed()).isFalse();
        assertThat(check(twoReleases, deprecated, release("1.1.0", ir(1, DEPRECATED + NOTE)),
                release("2.0.0", ir(2, DEPRECATED + NOTE))).passed()).isTrue();
    }

    @Test
    void aPolicyOfZeroAllowsAnyRemoval() {
        ReleasePolicy.Policy none = new ReleasePolicy.Policy(0, 0, true);

        assertThat(check(none, release("1.0.0", ir(1, PLAIN)), release("1.1.0", ir(1, ""))).passed()).isTrue();
    }

    @Test
    void minMajorsNeedsADeprecationMajorEvenWithoutMinReleases() {
        ReleasePolicy.Policy majorOnly = new ReleasePolicy.Policy(0, 1, true);

        assertThat(check(majorOnly, release("1.0.0", ir(1, PLAIN)), release("2.0.0", ir(2, ""))).passed()).isFalse();
    }

    @Test
    void anOperationRemovalNeedsItsDeprecation() {
        ContractIr withLegacyOperation = ir(1, "");
        ContractIr operationGone = new ContractIr(withLegacyOperation.irVersion(), withLegacyOperation.apiBasePath(),
                2, withLegacyOperation.entities(), List.of());

        ReleasePolicy.Result result = check(ReleasePolicy.Policy.DEFAULT,
                release("1.0.0", withLegacyOperation), release("2.0.0", operationGone));

        assertThat(result.violations()).extracting(ReleasePolicy.Violation::path).containsExactly(FIND);
        assertThat(result.violations().get(0).remedy()).contains("@AgenticExposed(apiDeprecatedSince = N)");
    }

    @Test
    void aDeprecatedOperationRemovedInALaterMajorPasses() {
        String attributes = ", apiDeprecatedSince = 1, apiUntil = 1, apiReplacement = \"findV2\"";
        ContractIr v1 = ReleaseFixtures.parse(ReleaseFixtures.irJson(1, "", attributes));
        ContractIr v2 = ReleaseFixtures.parse(ReleaseFixtures.irJson(2, "", attributes));

        ReleasePolicy.Result result = check(ReleasePolicy.Policy.DEFAULT, release("1.0.0", v1), release("2.0.0", v2));

        assertThat(result.passed()).as(result.violations().toString()).isTrue();
        assertThat(result.evidence()).containsKey(FIND);
    }

    @Test
    void aChannelLossIsARemovalSatisfiedOnlyByTheFieldsDeprecation() {
        String narrowed = NOTE.replace("description = \"A note\"",
                "description = \"A note\", channels = AgenticExposed.Channel.API");
        ContractIr previous = ir(1, NOTE);
        ContractIr current = ReleaseFixtures.parse(ReleaseFixtures.irJson(1, narrowed, "", ReleaseFixtures.PROJECTIONS));

        ReleasePolicy.Result result = check(new ReleasePolicy.Policy(1, 0, false),
                release("1.0.0", previous), release("1.1.0", current));

        assertThat(result.violations()).singleElement().satisfies(v -> {
            assertThat(v.path()).isEqualTo("field test.Order#note");
            assertThat(v.change()).isEqualTo("channels.AI");
            assertThat(v.evidence()).isEqualTo("loses a channel, [AI, API] → [API], in API major 1, but never released"
                    + " as deprecated");
            assertThat(v.remedy()).contains("A channel has no lifecycle of its own, so deprecate the whole field.");
        });
    }

    @Test
    void aWholeContractDisappearingRemovesEachElement() {
        ContractIr previous = ir(1, PLAIN);

        ReleasePolicy.Result result = check(ReleasePolicy.Policy.DEFAULT,
                release("1.0.0", previous), release("1.1.0", EmptyContract.document("/api", 1)));

        assertThat(result.violations()).extracting(ReleasePolicy.Violation::path)
                .containsExactly("field test.Order#id", FIELD, FIND);
    }

    @Test
    void anotherBreakingChangeFailsWithinTheSameMajor() {
        ContractIr previous = ir(1, PLAIN);
        ContractIr current = ir(1, PLAIN.replace("private String legacy", "private Integer legacy")
                .replace("public String getLegacy", "public Integer getLegacy"));

        ReleasePolicy.Result result = check(ReleasePolicy.Policy.DEFAULT, release("1.0.0", previous),
                release("1.1.0", current));

        assertThat(result.violations()).singleElement().satisfies(v -> {
            assertThat(v.path()).isEqualTo(FIELD);
            assertThat(v.change()).isEqualTo("javaType");
            assertThat(v.evidence()).isEqualTo("breaking within API major 1, javaType java.lang.String →"
                    + " java.lang.Integer (output): The field's schema in responses changes");
            assertThat(v.remedy()).contains("declare @AgenticField(removedInVersion = 2)",
                    "or release it under API major 2", "or set failOnBreaking = false");
        });
        assertThat(check(new ReleasePolicy.Policy(1, 1, false), release("1.0.0", previous),
                release("1.1.0", current)).passed()).isTrue();
    }

    @Test
    void anotherBreakingChangeAcrossAMajorIsOnlyListed() {
        ContractIr previous = ir(1, PLAIN);
        ContractIr current = ir(2, PLAIN.replace("private String legacy", "private Integer legacy")
                .replace("public String getLegacy", "public Integer getLegacy"));

        assertThat(check(ReleasePolicy.Policy.DEFAULT, release("1.0.0", previous), release("2.0.0", current))
                .passed()).isTrue();
    }

    @Test
    void refusesNegativeMinimumsAndAnEmptyHistory() {
        assertThatThrownBy(() -> new ReleasePolicy.Policy(-1, 1, true)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be 0 or more");
        assertThatThrownBy(() -> ReleasePolicy.check(List.of(), List.of(), ReleasePolicy.Policy.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Checks the last release against the one before it, or the empty document. */
    private static ReleasePolicy.Result check(ReleasePolicy.Policy policy, ReleasePolicy.Release... releases) {
        List<ReleasePolicy.Release> history = new ArrayList<>(List.of(releases));
        ContractIr current = history.get(history.size() - 1).ir();
        ContractIr previous = history.size() > 1 ? history.get(history.size() - 2).ir()
                : EmptyContract.document(current.apiBasePath(), current.apiMajor());
        return ReleasePolicy.check(history, ContractGate.compareReleases(previous, current), policy);
    }
}
