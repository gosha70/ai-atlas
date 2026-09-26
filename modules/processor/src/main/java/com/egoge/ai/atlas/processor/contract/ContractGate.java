/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.palantir.javapoet.TypeName;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The compatibility gate (FR-008..FR-013): compares a committed baseline IR with the fresh IR of
 * the compilation, both projected at M, the baseline's published {@code apiMajor}, and classifies
 * every difference as breaking or compatible by direction. Output differences concern entity
 * fields and operation returns (FR-009), input differences operations and parameters (FR-010).
 *
 * <p>The comparison reads only the two documents: no network, database or model call.
 */
public final class ContractGate {

    /** Class-output-relative path of the difference report (FR-013). */
    public static final String DIFF_RESOURCE_PATH = "META-INF/ai-atlas/contract-diff.json";
    /** Element path prefix of an entity: {@code entity <qualified class>}. */
    public static final String ENTITY_PATH = "entity ";
    /** Element path prefix of a field: {@code field <qualified class>#<java name>}. */
    public static final String FIELD_PATH = "field ";
    /** Element path prefix of an operation: {@code operation <qualified class>#<method>(<parameter types>)}. */
    public static final String OPERATION_PATH = "operation ";
    /** Element path of document-level attributes. */
    public static final String DOCUMENT_PATH = "document";
    /** The Gradle task that accepts the current contract as the new baseline. */
    public static final String ACCEPT_TASK = "atlasAccept";

    private static final String PREFIX = "[ai-atlas] ";
    private static final String ABSENT = "(none)";


    private static final String K_PUBLISHED_MAJOR = "publishedMajor";
    private static final String K_DIFFERENCES = "differences";
    private static final String K_PATH = "path";
    private static final String K_CHANGE = "change";
    private static final String K_DIRECTION = "direction";
    private static final String K_BEFORE = "before";
    private static final String K_AFTER = "after";
    private static final String K_CLASSIFICATION = "classification";

    private ContractGate() {
    }

    /** Which side of the API a difference affects. */
    public enum Direction {
        /** What clients send: operations, their parameters and addresses. */
        INPUT,
        /** What clients receive: entity fields and operation returns. */
        OUTPUT;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Whether a difference breaks clients of the published major. */
    public enum Classification {
        BREAKING,
        COMPATIBLE;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One difference between the baseline and fresh projections at M.
     *
     * @param path           the element path, e.g. {@code field shop.Order#total}
     * @param change         the attribute that differs, {@code removed} or {@code added}
     * @param direction      input or output
     * @param before         the baseline value, {@code null} when absent
     * @param after          the fresh value, {@code null} when absent
     * @param classification breaking or compatible
     * @param reason         why it breaks clients of M; {@code null} when compatible
     * @param remedy         the declaration that would legitimise it; {@code null} when compatible
     */
    public record Difference(String path, String change, Direction direction, String before, String after,
                             Classification classification, String reason, String remedy) {

        /** Whether this difference fails the build. */
        public boolean breaking() {
            return classification == Classification.BREAKING;
        }
    }

    /**
     * A diagnostic of the gate.
     *
     * @param kind        ERROR, WARNING or NOTE
     * @param elementPath the element the diagnostic concerns, or {@code null}
     * @param message     the full message, prefixed {@code [ai-atlas]}
     */
    public record Finding(Diagnostic.Kind kind, String elementPath, String message) {
    }

    /**
     * The result of checking a baseline.
     *
     * @param findings       the diagnostics to report, in order
     * @param publishedMajor the baseline's major M, or {@code 0} when no comparison ran
     * @param differences    every difference, ordered by element path; empty when no comparison ran
     * @param diffJson       the {@link #DIFF_RESOURCE_PATH} document, or {@code null} when no comparison ran
     */
    public record Outcome(List<Finding> findings, int publishedMajor, List<Difference> differences,
                          String diffJson) {

        public Outcome {
            findings = List.copyOf(findings);
            differences = List.copyOf(differences);
        }

        /** Whether the gate failed: any finding is an ERROR. */
        public boolean failed() {
            return findings.stream().anyMatch(f -> f.kind() == Diagnostic.Kind.ERROR);
        }

        /** Whether the baseline was compared with the fresh IR. */
        public boolean compared() {
            return diffJson != null;
        }
    }

    /**
     * Checks {@code fresh} against the baseline named by {@code baselineOption} (FR-008). With no
     * option, or no file at the path, nothing is compared and one NOTE names the expected path and
     * {@value #ACCEPT_TASK}.
     *
     * @param baselineOption the {@code ai.atlas.contract.baseline} value, or {@code null}
     * @param fresh          the IR of the current compilation; its {@code apiMajor} is the configured major
     * @return the findings, differences and difference report
     */
    public static Outcome check(String baselineOption, ContractIr fresh) {
        if (baselineOption == null || baselineOption.isBlank()) {
            return notCompared(new Finding(Diagnostic.Kind.NOTE, null, PREFIX + "No contract baseline is configured ("
                    + AgenticProcessor.OPT_CONTRACT_BASELINE + "), so the compatibility gate did not run. Run "
                    + ACCEPT_TASK + " to write one; the Gradle plugin expects it at .atlas/api.ir.json"));
        }
        Path path = Path.of(baselineOption);
        if (!Files.exists(path)) {
            return notCompared(new Finding(Diagnostic.Kind.NOTE, null, PREFIX + "No contract baseline at " + path
                    + ", so the compatibility gate did not run. Run " + ACCEPT_TASK
                    + " to write it from the current sources"));
        }
        ContractIr baseline;
        try {
            baseline = IrJson.read(path);
        } catch (IrJson.IrReadException e) {
            return notCompared(new Finding(Diagnostic.Kind.ERROR, null, PREFIX + e.getMessage()));
        }
        int published = baseline.apiMajor();
        if (fresh.apiMajor() < published) {
            return notCompared(new Finding(Diagnostic.Kind.ERROR, null, PREFIX + AgenticProcessor.OPT_API_MAJOR
                    + "=" + fresh.apiMajor() + " is below the published major " + published
                    + " of the contract baseline " + path + " — a build must not publish an older major than its"
                    + " baseline. Set " + AgenticProcessor.OPT_API_MAJOR + " to " + published
                    + " or above, or accept the change explicitly with " + ACCEPT_TASK));
        }
        List<Difference> differences;
        try {
            differences = compare(baseline, fresh);
        } catch (IllegalArgumentException e) {
            return notCompared(new Finding(Diagnostic.Kind.ERROR, null, PREFIX + "Contract baseline " + path
                    + " is not valid Contract IR JSON: " + e.getMessage()));
        }
        List<Finding> findings = new ArrayList<>();
        for (Difference difference : differences) {
            if (difference.breaking()) {
                findings.add(new Finding(Diagnostic.Kind.ERROR, difference.path(), message(difference, published)));
            }
        }
        return new Outcome(findings, published, differences, diffJson(published, differences));
    }

    private static Outcome notCompared(Finding finding) {
        return new Outcome(List.of(finding), 0, List.of(), null);
    }

    /**
     * Compares the projections of {@code baseline} and {@code fresh} at the baseline's
     * {@code apiMajor}, classifying every difference (FR-009, FR-010).
     *
     * @param baseline the committed baseline
     * @param fresh    the current IR
     * @return every difference, ordered by element path, then attribute
     * @throws IllegalArgumentException if a document carries a type string that is not canonical
     */
    public static List<Difference> compare(ContractIr baseline, ContractIr fresh) {
        return new ContractComparison(baseline, fresh).run();
    }

    /**
     * The {@link #DIFF_RESOURCE_PATH} document: every difference with its element path, attribute,
     * direction, before and after values and classification, in the given order (FR-013).
     *
     * @param publishedMajor the baseline's major M
     * @param differences    the differences, ordered by element path
     * @return canonical JSON text, with an empty list when there are no differences
     */
    public static String diffJson(int publishedMajor, List<Difference> differences) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(K_PUBLISHED_MAJOR, publishedMajor);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Difference d : differences) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(K_PATH, d.path());
            entry.put(K_CHANGE, d.change());
            entry.put(K_DIRECTION, d.direction().label());
            entry.put(K_BEFORE, d.before());
            entry.put(K_AFTER, d.after());
            entry.put(K_CLASSIFICATION, d.classification().label());
            list.add(entry);
        }
        doc.put(K_DIFFERENCES, list);
        return IrJson.writeCanonical(doc);
    }

    /**
     * The compile error for a breaking difference (FR-012): element path, the change as
     * before → after, the direction and why it breaks clients of M, the declaration that would
     * legitimise it, and {@value #ACCEPT_TASK}.
     */
    static String message(Difference d, int published) {
        return PREFIX + "Breaking contract change to " + d.path() + ": " + d.change() + " "
                + display(d.before()) + " → " + display(d.after()) + " (" + d.direction().label() + "). "
                + d.reason() + ", which breaks clients of major " + published + ". To make it legitimate, "
                + d.remedy() + "; or accept the change explicitly with " + ACCEPT_TASK + ".";
    }

    private static String display(String value) {
        return value != null ? value : ABSENT;
    }

    // ---------------------------------------------------------------- reporting in the processor

    /**
     * Checks {@code fresh} against the baseline named by the compilation's
     * {@code ai.atlas.contract.baseline}, reports every finding on its element when that still
     * exists in the compilation (FR-012), and writes {@link #DIFF_RESOURCE_PATH} to the class
     * output when a comparison ran (FR-013).
     *
     * @param env   the processing environment of the compilation
     * @param fresh the compilation's IR
     */
    public static void run(ProcessingEnvironment env, ContractIr fresh) {
        Outcome outcome = check(env.getOptions().get(AgenticProcessor.OPT_CONTRACT_BASELINE), fresh);
        Messager messager = env.getMessager();
        for (Finding finding : outcome.findings()) {
            Element element = finding.elementPath() != null ? locate(env, finding.elementPath()) : null;
            if (element != null) {
                messager.printMessage(finding.kind(), finding.message(), element);
            } else {
                messager.printMessage(finding.kind(), finding.message());
            }
        }
        if (!outcome.compared()) {
            return;
        }
        try {
            var resource = env.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", DIFF_RESOURCE_PATH);
            try (OutputStream out = resource.openOutputStream()) {
                out.write(outcome.diffJson().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + "Failed to write " + DIFF_RESOURCE_PATH + ": "
                    + e.getMessage());
        }
    }

    /** The element an element path names, or {@code null} when it no longer exists. */
    private static Element locate(ProcessingEnvironment env, String path) {
        if (path.startsWith(ENTITY_PATH)) {
            return env.getElementUtils().getTypeElement(path.substring(ENTITY_PATH.length()));
        }
        boolean field = path.startsWith(FIELD_PATH);
        if (!field && !path.startsWith(OPERATION_PATH)) {
            return null;
        }
        String target = path.substring(field ? FIELD_PATH.length() : OPERATION_PATH.length());
        int hash = target.indexOf('#');
        String member = target.substring(hash + 1);
        // A field may be inherited, so walk the superclass chain; an operation is declared on its service
        for (TypeElement type = env.getElementUtils().getTypeElement(target.substring(0, hash)); type != null;
             type = field ? superclass(type) : null) {
            for (Element enclosed : type.getEnclosedElements()) {
                if (field ? enclosed.getKind() == ElementKind.FIELD && enclosed.getSimpleName().contentEquals(member)
                        : enclosed.getKind() == ElementKind.METHOD
                        && member.equals(signature((ExecutableElement) enclosed))) {
                    return enclosed;
                }
            }
        }
        return null;
    }

    /** {@code method(parameter types)}, as {@link ContractIr.Operation#signature()} identifies it. */
    private static String signature(ExecutableElement method) {
        return method.getSimpleName() + "(" + String.join(",", method.getParameters().stream()
                .map(p -> TypeName.get(p.asType()).toString()).toList()) + ")";
    }

    private static TypeElement superclass(TypeElement type) {
        return type.getSuperclass() instanceof DeclaredType declared ? (TypeElement) declared.asElement() : null;
    }
}
