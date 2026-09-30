/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.util.VersionConfig;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.util.List;
import java.util.Map;

import static com.egoge.ai.atlas.processor.AgenticProcessor.OPT_COLLECTIONS;
import static com.egoge.ai.atlas.processor.AgenticProcessor.OPT_CONSTRAINTS;
import static com.egoge.ai.atlas.processor.AgenticProcessor.OPT_PROJECTIONS;

/**
 * A compilation's effective {@code ai.atlas.*} configuration: the version options every generator
 * reads, as {@link VersionConfig} resolves them, plus whether constraints and projections are on.
 * {@link #fromArguments} reads a plain map, such as one built from {@code compileJava}'s effective
 * compiler arguments, so the Gradle plugin parses the options exactly as the processor does.
 *
 * @param apiBasePath        the REST base path, defaulting to {@code /api}
 * @param apiMajor           the configured major, defaulting to 1
 * @param openApiInfoVersion the OpenAPI document's {@code info.version}, defaulting to {@code <apiMajor>.0.0}
 * @param constraints        whether {@value com.egoge.ai.atlas.processor.AgenticProcessor#OPT_CONSTRAINTS} is on
 * @param projections        whether {@value com.egoge.ai.atlas.processor.AgenticProcessor#OPT_PROJECTIONS} is on
 * @param collections        whether {@value com.egoge.ai.atlas.processor.AgenticProcessor#OPT_COLLECTIONS} is on,
 *                           which changes the IR's bounds, the OpenAPI document and the MCP schemas
 */
public record EffectiveOptions(String apiBasePath, int apiMajor, String openApiInfoVersion, boolean constraints,
                               boolean projections, boolean collections) {

    /** Discards diagnostics: {@link #fromArguments} reports an invalid option by returning {@code null}. */
    private static final Messager SILENT = new Messager() {
        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) {
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) {
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a,
                                 AnnotationValue v) {
        }
    };

    /**
     * Resolves options already reduced to their last value, such as {@code compileJava}'s effective
     * compiler arguments. Silent: an invalid option gives {@code null}, with no diagnostic. It also
     * rejects a constraints, projections or collections flag that is neither {@code true} nor {@code false},
     * which the processor, when it runs, reports itself.
     *
     * @param lastWinsOptions the options, one value per key
     * @return the effective options, or {@code null} when an option is invalid
     */
    public static EffectiveOptions fromArguments(Map<String, String> lastWinsOptions) {
        for (String flag : List.of(OPT_CONSTRAINTS, OPT_PROJECTIONS, OPT_COLLECTIONS)) {
            String value = lastWinsOptions.get(flag);
            if (value != null && !"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                return null;
            }
        }
        VersionConfig version = VersionConfig.resolve(lastWinsOptions, SILENT);
        if (version == null) {
            return null;
        }
        return new EffectiveOptions(version.apiBasePath(), version.apiMajor(), version.openApiInfoVersion(),
                "true".equalsIgnoreCase(lastWinsOptions.get(OPT_CONSTRAINTS)),
                "true".equalsIgnoreCase(lastWinsOptions.get(OPT_PROJECTIONS)),
                "true".equalsIgnoreCase(lastWinsOptions.get(OPT_COLLECTIONS)));
    }
}
