/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
import com.egoge.ai.atlas.annotations.AgenticField;
import com.egoge.ai.atlas.processor.util.FieldScanner;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code ai.atlas.projections} option of a compilation: {@code true} or {@code false} in any
 * case, default {@code false}. With it on, {@code @AgenticField(channels)} narrows the channels
 * whose responses carry a field, and each response is projected to the fields eligible for the
 * channel serving it:
 *
 * <pre>
 * field eligible channels  x  operation exposed channels  =  effective response projection
 * </pre>
 *
 * <p>With it off, generation is exactly what it was before this option, and an explicit
 * {@code @AgenticField(channels)} is an ERROR: the field would still be served on every channel,
 * turning a safety declaration into a silent no-op.
 */
public final class ProjectionsOption {

    // Plain strings, not the annotation enum: initialising the processor loads no annotation class
    /** The MCP channel. */
    public static final String AI = "AI";
    /** The REST channel. */
    public static final String API = "API";
    /** Every channel, sorted: the eligibility of a field that declares none. */
    public static final List<String> EVERY_CHANNEL = List.of(AI, API);

    private final boolean enabled;
    private final String option;
    /** Declared eligibility by {@code entity#field}, sorted; a field absent from the map is on every channel. */
    private final Map<String, List<String>> eligibility = new HashMap<>();

    private ProjectionsOption(boolean enabled, String option) {
        this.enabled = enabled;
        this.option = option;
    }

    /**
     * Reads the option, reporting an ERROR naming it and its value when it is neither
     * {@code true} nor {@code false}.
     *
     * @param option the option name
     * @param env    the processing environment
     * @return the option, or {@code null} after reporting an invalid value
     */
    public static ProjectionsOption resolve(String option, ProcessingEnvironment env) {
        String value = env.getOptions().get(option);
        if (value == null || "false".equalsIgnoreCase(value)) {
            return new ProjectionsOption(false, option);
        }
        if (!"true".equalsIgnoreCase(value)) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] " + option + " must be 'true' or 'false'. Got: " + value);
            return null;
        }
        return new ProjectionsOption(true, option);
    }

    /** Whether the option is on. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Validates and records the declared eligibility of an entity's fields. An empty
     * {@code channels}, or {@code INHERIT} mixed with explicit values, is an ERROR on the field; so
     * is any explicit value with the option off. A field in error keeps every channel.
     *
     * @param entity   the {@code @AgenticEntity} class
     * @param scanned  the entity's recorded {@code @AgenticField} fields
     * @param messager receives the diagnostics
     */
    public void recordEntity(TypeElement entity, List<FieldScanner.ScannedField> scanned, Messager messager) {
        String className = entity.getQualifiedName().toString();
        for (FieldScanner.ScannedField field : scanned) {
            List<Channel> declared = Arrays.asList(field.element().getAnnotation(AgenticField.class).channels());
            if (declared.equals(List.of(Channel.INHERIT))) {
                continue;
            }
            String subject = "@AgenticField(channels) on field '" + field.model().name() + "' of "
                    + entity.getSimpleName();
            if (declared.isEmpty()) {
                messager.printMessage(Diagnostic.Kind.ERROR, "[ai-atlas] " + subject + " must not be empty."
                        + " Omit it to keep the field on every channel", field.element());
            } else if (declared.contains(Channel.INHERIT)) {
                messager.printMessage(Diagnostic.Kind.ERROR, "[ai-atlas] " + subject
                        + " must not mix INHERIT with explicit values", field.element());
            } else if (!enabled) {
                messager.printMessage(Diagnostic.Kind.ERROR, "[ai-atlas] " + subject + " requires " + option
                        + "=true. Without it the field is served on every channel. Turn the option on"
                        + " (agentic { projections = true } in Gradle) or remove the declaration", field.element());
            } else {
                eligibility.put(key(className, field.model().name()),
                        declared.stream().map(Enum::name).distinct().sorted().toList());
            }
        }
    }

    /**
     * The channels whose responses carry a field: its declared eligibility with the option on,
     * every channel otherwise.
     *
     * @param className qualified name of the entity class
     * @param fieldName Java field name
     * @return the channel names, sorted
     */
    public List<String> channels(String className, String fieldName) {
        return eligibility.getOrDefault(key(className, fieldName), EVERY_CHANNEL);
    }

    private static String key(String className, String fieldName) {
        return className + "#" + fieldName;
    }
}
