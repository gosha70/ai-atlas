/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.release;

import com.egoge.ai.atlas.processor.contract.ChannelReachability;
import com.egoge.ai.atlas.processor.contract.ContractGate;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.ReleaseComparison;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The release policy: what may leave a published contract between two releases. It reads the
 * differences {@link ReleaseComparison#compare} reports and the release history, and never
 * classifies a change itself.
 *
 * <ul>
 *   <li>A <em>removal</em> is a field or operation the previous release published and this one does
 *       not, or a channel it is reachable on that it loses: the gate's breaking {@code removed},
 *       {@code channels.<channel>} (field) and {@code channels} (operation) differences. A
 *       module's whole contract disappearing removes each of its elements. A removal needs the
 *       element to have been released deprecated in at least {@link Policy#minReleases()}
 *       <strong>published</strong> releases, with at least {@link Policy#minMajors()} majors
 *       between its deprecation major and the major of this release. Only a release with
 *       {@link Release#published()} counts as evidence. An entity's removal is the removal of its
 *       fields, which are checked one by one. A channel has no lifecycle of its own, so a channel
 *       removal is satisfied only by published releases where the field or operation was, at that
 *       release's own major, active, deprecated <strong>and visible on the lost channel</strong>,
 *       using {@link ChannelReachability}. A release where the element was deprecated but not
 *       visible on that channel earns no credit for removing it.</li>
 *   <li>Any other breaking difference fails under {@link Policy#failOnBreaking()} when this release
 *       has the same {@code apiMajor} as the previous one. Across a major it is expected, and only
 *       listed in the changelog.</li>
 * </ul>
 */
public final class ReleasePolicy {

    /** Where a Gradle build configures the policy, named in every remedy. */
    public static final String CONFIGURATION = "agentic { release { deprecation { minReleases; minMajors;"
            + " failOnBreaking } } }";

    private static final String REMOVED = "removed";
    private static final String CHANNELS = "channels";

    private ReleasePolicy() {
    }

    /**
     * The policy's settings.
     *
     * @param minReleases    releases an element must have been published in while deprecated
     *                       before a release may remove it
     * @param minMajors      majors between an element's deprecation major and the major of the
     *                       release that removes it
     * @param failOnBreaking whether any other breaking difference fails a release with the same
     *                       {@code apiMajor} as the previous one
     */
    public record Policy(int minReleases, int minMajors, boolean failOnBreaking) {

        /** One release deprecated, one major, and breaking changes within a major refused. */
        public static final Policy DEFAULT = new Policy(1, 1, true);

        /**
         * @throws IllegalArgumentException if a minimum is negative
         */
        public Policy {
            if (minReleases < 0 || minMajors < 0) {
                throw new IllegalArgumentException("The release deprecation policy's minReleases and minMajors must"
                        + " be 0 or more, got " + minReleases + " and " + minMajors);
            }
        }
    }

    /**
     * A release: its version and the IR it published.
     *
     * @param version   the version
     * @param ir        the IR, migrated in memory to the current {@code irVersion}
     * @param published whether the release is backed by a proved tag; only a published release
     *                  earns deprecation credit (D10.1). Transitional: every release is published
     *                  until D4 wires the real publication verdict in.
     */
    public record Release(ReleaseVersion version, ContractIr ir, boolean published) {
    }

    /**
     * What the release history says about a removed element's deprecation.
     *
     * @param deprecatedReleases     the earlier releases that published the element deprecated
     * @param deprecatedSince        the earliest deprecation major those releases published, {@code 0} if none
     * @param firstDeprecatedRelease the first of those releases, or {@code null}
     */
    public record Evidence(int deprecatedReleases, int deprecatedSince, ReleaseVersion firstDeprecatedRelease) {

        /** Whether any earlier release published the element deprecated. */
        public boolean deprecated() {
            return deprecatedReleases > 0;
        }
    }

    /**
     * One element the release may not change as it does.
     *
     * @param path     the element path, as the gate names it
     * @param change   the gate's attribute that changed, such as {@code removed}
     * @param evidence what was found, such as "never released as deprecated"
     * @param remedy   how to make the release pass
     */
    public record Violation(String path, String change, String evidence, String remedy) {

        /** The violation as one line of a failure message. */
        public String message() {
            return path + " (" + change + "): " + evidence + ". " + remedy + ".";
        }
    }

    /**
     * The policy's verdict on a release.
     *
     * @param violations the violations, in the order of the differences
     * @param evidence   the deprecation evidence of every field and operation removal, by element path
     */
    public record Result(List<Violation> violations, Map<String, Evidence> evidence) {

        public Result {
            violations = List.copyOf(violations);
            evidence = Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
        }

        /** Whether the release satisfies the policy. */
        public boolean passed() {
            return violations.isEmpty();
        }
    }

    /**
     * Whether a difference removes a published element, or a channel one is reachable on.
     *
     * @param d a difference {@link ReleaseComparison#compare} reported
     * @return whether it is a removal the policy governs
     */
    public static boolean isRemoval(ContractGate.Difference d) {
        if (!d.breaking()) {
            return false;
        }
        String path = d.path();
        if (REMOVED.equals(d.change())) {
            return ReleaseElements.isField(path) || ReleaseElements.isOperation(path) || ReleaseElements.isEntity(path);
        }
        return ReleaseElements.isField(path) ? d.change().startsWith(CHANNELS + ".")
                : ReleaseElements.isOperation(path) && CHANNELS.equals(d.change());
    }

    /**
     * Checks a release against the policy.
     *
     * @param history     every release, oldest first, ending with the one being checked
     * @param differences what {@link ReleaseComparison#compare} reported between the last two
     *                    releases of {@code history}, or between the empty document and the only one
     * @param policy      the policy
     * @return the violations, and the deprecation evidence of every removal
     * @throws IllegalArgumentException if {@code history} is empty
     */
    public static Result check(List<Release> history, List<ContractGate.Difference> differences, Policy policy) {
        if (history.isEmpty()) {
            throw new IllegalArgumentException("The release history must end with the release being checked");
        }
        Release current = history.get(history.size() - 1);
        List<Release> earlier = history.subList(0, history.size() - 1);
        Release previous = earlier.isEmpty() ? null : earlier.get(earlier.size() - 1);
        int major = current.ir().apiMajor();
        boolean sameMajor = previous != null && previous.ir().apiMajor() == major;
        List<Violation> violations = new ArrayList<>();
        Map<String, Evidence> evidence = new LinkedHashMap<>();
        for (ContractGate.Difference d : differences) {
            if (isRemoval(d)) {
                if (ReleaseElements.isEntity(d.path())) {
                    continue; // its fields are removed too, each checked with its own evidence
                }
                if (REMOVED.equals(d.change())) {
                    Evidence found = evidence(d.path(), earlier);
                    evidence.put(d.path(), found);
                    if (!satisfied(found, major, policy)) {
                        violations.add(new Violation(d.path(), d.change(), describe(found, d, major),
                                removalRemedy(d, policy)));
                    }
                } else {
                    channelViolation(d, earlier, major, policy, violations, evidence);
                }
            } else if (d.breaking() && sameMajor && policy.failOnBreaking()) {
                violations.add(new Violation(d.path(), d.change(), "breaking within API major " + major + ", "
                        + d.change() + " " + value(d.before()) + " → " + value(d.after()) + " ("
                        + d.direction().name().toLowerCase(Locale.ROOT) + "): " + d.reason(),
                        "To release it, " + d.remedy() + "; or release it under API major " + (major + 1)
                        + "; or set failOnBreaking = false in " + CONFIGURATION));
            }
        }
        return new Result(violations, evidence);
    }

    /** The earlier published releases that published the element deprecated. */
    private static Evidence evidence(String path, List<Release> earlier) {
        int count = 0;
        int since = 0;
        ReleaseVersion first = null;
        for (Release release : earlier) {
            if (!release.published()) {
                continue;
            }
            int deprecation = ReleaseElements.publishedDeprecation(release.ir(), path);
            if (deprecation > 0) {
                count++;
                since = since == 0 ? deprecation : Math.min(since, deprecation);
                first = first == null ? release.version() : first;
            }
        }
        return new Evidence(count, since, first);
    }

    /**
     * Checks a field or operation losing one or more channels: a difference with change
     * {@code channels.<C>} (field, exactly one channel) or {@code channels} (operation, possibly
     * several). Credit for each lost channel counts only the earlier published releases where the
     * element was active, deprecated, and visible on that channel (D4.7, D4.9). The difference
     * passes only when every lost channel does; the first channel that does not names the
     * violation.
     */
    private static void channelViolation(ContractGate.Difference d, List<Release> earlier, int major, Policy policy,
                                         List<Violation> violations, Map<String, Evidence> evidence) {
        String failingChannel = null;
        Evidence reported = null;
        for (String channel : lostChannels(d)) {
            Evidence found = channelEvidence(d.path(), channel, earlier);
            reported = found;
            failingChannel = channel;
            if (!satisfied(found, major, policy)) {
                break;
            }
            failingChannel = null;
        }
        if (reported == null) {
            return;
        }
        evidence.put(d.path(), reported);
        if (failingChannel != null) {
            String change = ReleaseElements.isField(d.path()) ? d.change() : CHANNELS + "." + failingChannel;
            violations.add(new Violation(d.path(), change, describe(reported, d, major), removalRemedy(d, policy)));
        }
    }

    /** The earlier published releases where the element was active, deprecated and visible on {@code channel}. */
    private static Evidence channelEvidence(String path, String channel, List<Release> earlier) {
        boolean field = ReleaseElements.isField(path);
        String entityClass = field ? ReleaseElements.fieldClass(path) : null;
        String member = field ? ReleaseElements.fieldName(path) : ReleaseElements.operationId(path);
        int count = 0;
        int since = 0;
        ReleaseVersion first = null;
        for (Release release : earlier) {
            if (!release.published()) {
                continue;
            }
            int deprecation = ReleaseElements.publishedDeprecation(release.ir(), path);
            if (deprecation <= 0) {
                continue;
            }
            int ownMajor = release.ir().apiMajor();
            boolean visible = field
                    ? ChannelReachability.fieldVisible(release.ir(), ownMajor, entityClass, member, channel)
                    : ChannelReachability.operationListed(release.ir(), ownMajor, member, channel);
            if (visible) {
                count++;
                since = since == 0 ? deprecation : Math.min(since, deprecation);
                first = first == null ? release.version() : first;
            }
        }
        return new Evidence(count, since, first);
    }

    /** The channels {@code d} loses: the one named in a field's {@code channels.<C>}, or an operation's set. */
    private static List<String> lostChannels(ContractGate.Difference d) {
        if (ReleaseElements.isField(d.path())) {
            return List.of(d.change().substring((CHANNELS + ".").length()));
        }
        List<String> before = channelList(d.before());
        List<String> after = channelList(d.after());
        return before.stream().filter(c -> !after.contains(c)).toList();
    }

    /** Parses a rendered channel list such as {@code [AI, API]} back into its channel names. */
    private static List<String> channelList(String rendered) {
        if (rendered == null || rendered.strip().length() < 2) {
            return List.of();
        }
        String inner = rendered.strip();
        inner = inner.substring(1, inner.length() - 1).strip();
        return inner.isEmpty() ? List.of() : List.of(inner.split(",\\s*"));
    }

    private static boolean satisfied(Evidence found, int major, Policy policy) {
        return found.deprecatedReleases() >= policy.minReleases()
                && (policy.minMajors() == 0 || found.deprecated() && major - found.deprecatedSince() >= policy.minMajors());
    }

    private static String describe(Evidence found, ContractGate.Difference d, int major) {
        String what = REMOVED.equals(d.change()) ? "removed" : "loses a channel, " + value(d.before()) + " → "
                + value(d.after()) + ",";
        if (!found.deprecated()) {
            return what + " in API major " + major + ", but never released as deprecated";
        }
        return what + " in API major " + major + ", deprecated since major " + found.deprecatedSince()
                + " and released deprecated in " + found.deprecatedReleases() + " release(s) from "
                + found.firstDeprecatedRelease();
    }

    private static String removalRemedy(ContractGate.Difference d, Policy policy) {
        boolean field = ReleaseElements.isField(d.path());
        String declaration = field ? "@AgenticField(deprecatedSinceVersion = N)" : "@AgenticExposed(apiDeprecatedSince = N)";
        String channel = REMOVED.equals(d.change()) ? "" : " A channel has no lifecycle of its own, so deprecate the"
                + " whole " + (field ? "field" : "operation") + ".";
        return "The policy needs it released deprecated in at least " + policy.minReleases() + " release(s), and"
                + " removed at least " + policy.minMajors() + " major(s) after its deprecation major." + channel
                + " Restore it, declare " + declaration + " and release that before removing it; or relax "
                + CONFIGURATION;
    }

    private static String value(String value) {
        return value != null ? value : "(none)";
    }
}
