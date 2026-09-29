/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.release;

import com.egoge.ai.atlas.processor.contract.ContractGate;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.ReleaseComparison;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A release's Markdown changelog section, rendered from the differences
 * {@link ReleaseComparison#compare} reported. Every entry is one difference, named by the gate's
 * element path; the sections only group them, so the changelog says nothing the comparison did not.
 * There is no date, so the same release gives the same bytes; the date lives in version control.
 *
 * <p>The sections, in order, each only when it has an entry:
 * <ul>
 *   <li><b>Breaking</b>: breaking differences other than removals, with the gate's reason;</li>
 *   <li><b>Removed</b>: removed elements and lost channels, with their deprecation evidence;</li>
 *   <li><b>Deprecated</b>: elements this release publishes deprecated and the previous did not,
 *       with the removal major and the message or replacement;</li>
 *   <li><b>Added</b>, <b>Changed</b> (other compatible differences) and <b>Informational</b>.</li>
 * </ul>
 */
public final class ReleaseChangelog {

    /** The first line of the aggregate changelog. */
    public static final String AGGREGATE_TITLE = "# Contract changelog";

    private static final String NL = "\n";
    private static final String BREAKING = "Breaking";
    private static final String REMOVED = "Removed";
    private static final String DEPRECATED = "Deprecated";
    private static final String ADDED = "Added";
    private static final String CHANGED = "Changed";
    private static final String INFORMATIONAL = "Informational";

    private ReleaseChangelog() {
    }

    /**
     * Renders a release's section.
     *
     * @param version     the release's version
     * @param current     the release's IR
     * @param previous    the previous release, or {@code null} for the first one
     * @param differences the differences {@link ReleaseComparison#compare} reported
     * @param evidence    the deprecation evidence of each removal, from {@link ReleasePolicy#check}
     * @return the section, starting {@code ## <version> (API major N)} and ending with a newline
     */
    public static String render(ReleaseVersion version, ContractIr current, ReleasePolicy.Release previous,
                                List<ContractGate.Difference> differences,
                                Map<String, ReleasePolicy.Evidence> evidence) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        for (String name : List.of(BREAKING, REMOVED, DEPRECATED, ADDED, CHANGED, INFORMATIONAL)) {
            sections.put(name, new ArrayList<>());
        }
        for (ContractGate.Difference d : differences) {
            if (ReleasePolicy.isRemoval(d)) {
                sections.get(REMOVED).add(removed(d, evidence.get(d.path())));
            } else if (d.breaking()) {
                sections.get(BREAKING).add(changed(d) + " " + d.reason() + ".");
            } else if (d.classification() == ContractGate.Classification.INFORMATIONAL) {
                sections.get(INFORMATIONAL).add(changed(d));
            } else if ("added".equals(d.change())) {
                sections.get(ADDED).add(code(d.path()) + " (" + label(d) + (ReleaseElements.isField(d.path())
                        ? ", " + code(d.after()) : "") + ")");
            } else if ("lifecycle".equals(d.change()) && newlyDeprecated(current, previous, d.path())) {
                sections.get(DEPRECATED).add(code(d.path()) + ": " + deprecation(current, d.path()));
            } else {
                sections.get(CHANGED).add(changed(d));
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("## ").append(version).append(" (API major ").append(current.apiMajor()).append(')').append(NL)
                .append(NL);
        if (previous == null) {
            out.append("First release of this contract.").append(NL);
        } else {
            out.append("Compared with ").append(previous.version()).append(" (API major ")
                    .append(previous.ir().apiMajor()).append(").").append(NL);
        }
        boolean any = false;
        for (Map.Entry<String, List<String>> section : sections.entrySet()) {
            if (!section.getValue().isEmpty()) {
                any = true;
                out.append(NL).append("### ").append(section.getKey()).append(NL).append(NL);
                section.getValue().forEach(entry -> out.append("- ").append(entry).append(NL));
            }
        }
        if (!any) {
            out.append(NL).append("No contract changes.").append(NL);
        }
        return out.toString();
    }

    /**
     * The aggregate changelog of every release.
     *
     * @param sections each release's section, newest first
     * @return the document: a title and note, then the sections, separated by blank lines
     */
    public static String aggregate(List<String> sections) {
        StringBuilder out = new StringBuilder(AGGREGATE_TITLE).append(NL).append(NL)
                .append("Generated from the release snapshots next to this file, newest first. Do not edit it:")
                .append(" every release regenerates it.").append(NL);
        sections.forEach(section -> out.append(NL).append(section));
        return out.toString();
    }

    private static String removed(ContractGate.Difference d, ReleasePolicy.Evidence evidence) {
        String entry = code(d.path()) + " (" + label(d) + ")";
        if (!"removed".equals(d.change())) {
            entry += ": " + d.change() + " " + code(d.before()) + " → " + code(d.after());
        }
        if (evidence == null) {
            return entry; // an entity: its removal is the removal of its fields, listed with their own evidence
        }
        if (!evidence.deprecated()) {
            return entry + ", never released as deprecated";
        }
        return entry + ", deprecated since major " + evidence.deprecatedSince() + " (first released deprecated in "
                + evidence.firstDeprecatedRelease() + ", " + evidence.deprecatedReleases() + " release(s))";
    }

    private static String changed(ContractGate.Difference d) {
        return code(d.path()) + ": " + d.change() + " " + code(d.before()) + " → " + code(d.after()) + " ("
                + label(d) + ").";
    }

    private static boolean newlyDeprecated(ContractIr current, ReleasePolicy.Release previous, String path) {
        return ReleaseElements.publishedDeprecation(current, path) > 0
                && (previous == null || ReleaseElements.publishedDeprecation(previous.ir(), path) == 0);
    }

    /** The deprecation {@code current} publishes for a field or operation, with its removal major and note. */
    private static String deprecation(ContractIr current, String path) {
        int since = ReleaseElements.publishedDeprecation(current, path);
        int removedIn;
        String note;
        if (ReleaseElements.isField(path)) {
            ContractIr.FieldLifecycle life = ReleaseElements.field(current, path).lifecycle();
            removedIn = life.removedInVersion() == Integer.MAX_VALUE ? 0 : life.removedInVersion();
            note = life.deprecatedMessage();
        } else {
            ContractIr.OperationLifecycle life = ReleaseElements.operation(current, path).lifecycle();
            removedIn = life.apiUntil() == Integer.MAX_VALUE ? 0 : life.apiUntil() + 1;
            note = life.apiReplacement() == null || life.apiReplacement().isEmpty() ? null
                    : "Replacement: " + life.apiReplacement();
        }
        return "deprecated since major " + since + (removedIn > 0 ? ", removed in major " + removedIn : "")
                + (note == null || note.isEmpty() ? "" : ". " + note);
    }

    private static String code(String value) {
        return value == null ? "(none)" : "`" + value + "`";
    }

    private static String label(ContractGate.Difference d) {
        return d.direction().name().toLowerCase(Locale.ROOT);
    }
}
