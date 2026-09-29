/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.contract.ContractGate;
import com.egoge.ai.atlas.processor.contract.ContractIr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SPIKE: renders a release's Markdown changelog section from the gate's differences. Every entry
 * is one {@link ContractGate.Difference}; the sections only group them by classification and
 * change, so the changelog cannot say anything the compatibility comparison did not.
 */
final class ReleaseChangelog {

    private static final String NL = "\n";

    private ReleaseChangelog() {
    }

    static String render(ReleaseVersion version, ContractIr current, ReleaseAction.Release previous,
                         List<ContractGate.Difference> differences, Map<String, ReleaseAction.Evidence> evidence) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        for (String name : List.of("Breaking", "Removed", "Deprecated", "Added", "Changed", "Informational")) {
            sections.put(name, new ArrayList<>());
        }
        for (ContractGate.Difference d : differences) {
            boolean removed = "removed".equals(d.change());
            if (d.breaking() && removed) {
                sections.get("Removed").add(removed(d, evidence.get(d.path())));
            } else if (d.breaking()) {
                sections.get("Breaking").add(changed(d) + " " + d.reason() + ".");
            } else if (d.classification() == ContractGate.Classification.INFORMATIONAL) {
                sections.get("Informational").add(changed(d));
            } else if ("added".equals(d.change())) {
                sections.get("Added").add("`" + d.path() + "` (" + label(d.direction())
                        + (d.path().startsWith(ContractGate.FIELD_PATH) ? ", `" + d.after() + "`" : "") + ")");
            } else if ("lifecycle".equals(d.change()) && deprecation(current, d.path()) != null) {
                // A published surface keeps only the deprecation in effect, so its lifecycle changes only by deprecation
                sections.get("Deprecated").add("`" + d.path() + "`: " + deprecation(current, d.path()));
            } else {
                sections.get("Changed").add(changed(d));
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("## ").append(version).append(" (API major ").append(current.apiMajor()).append(")").append(NL)
                .append(NL);
        if (previous == null) {
            out.append("First release of this contract.").append(NL);
        } else {
            out.append("Compared with ").append(previous.version()).append(" (API major ")
                    .append(previous.ir().apiMajor()).append(") by the ai-atlas compatibility gate.").append(NL);
        }
        boolean any = false;
        for (Map.Entry<String, List<String>> section : sections.entrySet()) {
            if (section.getValue().isEmpty()) {
                continue;
            }
            any = true;
            out.append(NL).append("### ").append(section.getKey()).append(NL).append(NL);
            section.getValue().forEach(entry -> out.append("- ").append(entry).append(NL));
        }
        if (!any) {
            out.append(NL).append("No contract changes.").append(NL);
        }
        return out.toString();
    }

    private static String removed(ContractGate.Difference d, ReleaseAction.Evidence evidence) {
        String entry = "`" + d.path() + "` (" + label(d.direction()) + ")";
        if (evidence == null) {
            return entry; // an entity: its removal is the removal of its fields, listed with their own evidence
        }
        if (evidence.deprecatedReleases() == 0) {
            return entry + ", never released as deprecated";
        }
        return entry + ", deprecated since major " + evidence.deprecatedSince() + " (first released deprecated in "
                + evidence.firstDeprecatedRelease() + ", " + evidence.deprecatedReleases() + " release(s))";
    }

    private static String changed(ContractGate.Difference d) {
        return "`" + d.path() + "`: " + d.change() + " " + code(d.before()) + " → " + code(d.after()) + " ("
                + label(d.direction()) + ").";
    }

    /** The deprecation in effect for a field or operation at the current major, or {@code null}. */
    private static String deprecation(ContractIr current, String path) {
        int major = current.apiMajor();
        if (path.startsWith(ContractGate.FIELD_PATH)) {
            String target = path.substring(ContractGate.FIELD_PATH.length());
            int hash = target.indexOf('#');
            for (ContractIr.Entity e : current.entities()) {
                if (e.className().equals(target.substring(0, hash))) {
                    for (ContractIr.Field f : e.fields()) {
                        ContractIr.FieldLifecycle life = f.lifecycle();
                        if (f.name().equals(target.substring(hash + 1)) && life.deprecatedSinceVersion() > 0
                                && life.deprecatedSinceVersion() <= major) {
                            return describe(life.deprecatedSinceVersion(), life.deprecatedMessage(),
                                    life.removedInVersion() == Integer.MAX_VALUE ? 0 : life.removedInVersion());
                        }
                    }
                }
            }
        } else if (path.startsWith(ContractGate.OPERATION_PATH)) {
            String id = path.substring(ContractGate.OPERATION_PATH.length());
            for (ContractIr.Operation op : current.operations()) {
                ContractIr.OperationLifecycle life = op.lifecycle();
                if (op.id().equals(id) && life.apiDeprecatedSince() > 0 && life.apiDeprecatedSince() <= major) {
                    return describe(life.apiDeprecatedSince(), life.apiReplacement(),
                            life.apiUntil() == Integer.MAX_VALUE ? 0 : life.apiUntil() + 1);
                }
            }
        }
        return null;
    }

    private static String describe(int since, String note, int removedIn) {
        return "deprecated since major " + since + (removedIn > 0 ? ", removed in major " + removedIn : "")
                + (note == null || note.isEmpty() ? "" : ". " + note);
    }

    private static String code(String value) {
        return value == null ? "(none)" : "`" + value + "`";
    }

    private static String label(ContractGate.Direction direction) {
        return direction.name().toLowerCase(Locale.ROOT);
    }
}
