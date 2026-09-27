/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.constraints;

import com.egoge.ai.atlas.annotations.AgenticConstraints;
import com.egoge.ai.atlas.annotations.AgenticParam;
import com.egoge.ai.atlas.annotations.Requiredness;
import com.egoge.ai.atlas.processor.constraints.EffectiveConstraints.PatternConstraint;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads the effective constraints of {@code @AgenticField} fields and exposed-method parameters
 * (FR-002, FR-003) and reports FR-004's diagnostics on the element.
 *
 * <p>Jakarta Bean Validation annotations are identified by qualified name, so the processor has no
 * compile-time dependency on the validation API. They are intersected first, independently of the
 * order they are written in; {@link AgenticConstraints} then replaces the keys it sets.
 */
public final class ConstraintReader {

    private static final String PREFIX = "[ai-atlas] ";
    private static final String BV_PACKAGE = "jakarta.validation.constraints.";
    private static final String DEFAULT_GROUP = "jakarta.validation.groups.Default";
    private static final String LIST_SUFFIX = ".List";

    private static final String MIN = "Min";
    private static final String MAX = "Max";
    private static final String DECIMAL_MIN = "DecimalMin";
    private static final String DECIMAL_MAX = "DecimalMax";
    private static final String POSITIVE = "Positive";
    private static final String POSITIVE_OR_ZERO = "PositiveOrZero";
    private static final String NEGATIVE = "Negative";
    private static final String NEGATIVE_OR_ZERO = "NegativeOrZero";
    private static final String SIZE = "Size";
    private static final String PATTERN = "Pattern";
    private static final String NOT_NULL = "NotNull";
    private static final String NOT_BLANK = "NotBlank";
    private static final String NOT_EMPTY = "NotEmpty";
    private static final Set<String> READ = Set.of(MIN, MAX, DECIMAL_MIN, DECIMAL_MAX, POSITIVE, POSITIVE_OR_ZERO,
            NEGATIVE, NEGATIVE_OR_ZERO, SIZE, PATTERN, NOT_NULL, NOT_BLANK, NOT_EMPTY);

    private static final String A_VALUE = "value";
    private static final String A_GROUPS = "groups";
    private static final String A_INCLUSIVE = "inclusive";
    private static final String A_MIN = "min";
    private static final String A_MAX = "max";
    private static final String A_REGEXP = "regexp";
    private static final String A_FLAGS = "flags";

    /** The constraint keys (FR-005), as named in diagnostics. */
    public static final String K_MINIMUM = "minimum";
    public static final String K_EXCLUSIVE_MINIMUM = "exclusiveMinimum";
    public static final String K_MAXIMUM = "maximum";
    public static final String K_EXCLUSIVE_MAXIMUM = "exclusiveMaximum";
    public static final String K_MIN_LENGTH = "minLength";
    public static final String K_MAX_LENGTH = "maxLength";
    public static final String K_MIN_ITEMS = "minItems";
    public static final String K_MAX_ITEMS = "maxItems";
    public static final String K_PATTERNS = "patterns";
    public static final String K_NOT_BLANK = "notBlank";

    /** {@code @AgenticConstraints}' "not set" value of the int attributes. */
    private static final int UNSET = -1;

    /**
     * The effective contract of a parameter (FR-003).
     *
     * @param constraints the effective constraints
     * @param required    whether clients must pass the parameter
     * @param description {@code @AgenticParam(description)}, or {@code ""}
     */
    public record ParameterContract(EffectiveConstraints constraints, boolean required, String description) {
    }

    private final ProcessingEnvironment env;

    /**
     * @param env the processing environment, whose messager receives FR-004's diagnostics
     */
    public ConstraintReader(ProcessingEnvironment env) {
        this.env = env;
    }

    /**
     * Reads the effective constraints of an {@code @AgenticField} field, an output.
     *
     * @param field the field
     * @return its effective constraints
     */
    public EffectiveConstraints readField(VariableElement field) {
        return read(field, "field", false).constraints();
    }

    /**
     * Reads the effective contract of a parameter of an exposed method, an input.
     *
     * @param parameter the parameter
     * @return its effective contract
     */
    public ParameterContract readParameter(VariableElement parameter) {
        return read(parameter, "parameter", true);
    }

    private ParameterContract read(VariableElement element, String kind, boolean input) {
        String what = kind + " '" + element.getSimpleName() + "'";
        TypeMirror type = element.asType();
        ConstrainedType typeClass = ConstrainedType.of(type, env);
        Set<String> errors = new LinkedHashSet<>();

        Accumulator bv = new Accumulator();
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            String name = qualifiedName(mirror);
            if (!name.startsWith(BV_PACKAGE)) {
                continue;
            }
            String simple = name.substring(BV_PACKAGE.length());
            if (simple.endsWith(LIST_SUFFIX) && READ.contains(simple.substring(0, simple.length() - LIST_SUFFIX.length()))) {
                for (AnnotationMirror nested : annotations(values(mirror).get(A_VALUE))) {
                    readBeanValidation(simple.substring(0, simple.length() - LIST_SUFFIX.length()), nested,
                            typeClass, bv, what, errors);
                }
            } else if (READ.contains(simple)) {
                readBeanValidation(simple, mirror, typeClass, bv, what, errors);
            }
        }
        EffectiveConstraints intersected = bv.toConstraints();
        ConstraintChecks.check(intersected, typeClass, what + " (Bean Validation)", errors);

        AgenticConstraints override = element.getAnnotation(AgenticConstraints.class);
        EffectiveConstraints effective = override == null ? intersected
                : applyOverride(override, intersected, typeClass, what, input, element, errors);
        if (!effective.equals(intersected)) {
            ConstraintChecks.check(effective, typeClass, what, errors);
        }

        boolean required = true;
        String description = "";
        AgenticParam param = input ? element.getAnnotation(AgenticParam.class) : null;
        if (param != null) {
            description = param.description();
            if (param.required() == Requiredness.OPTIONAL) {
                if (type.getKind().isPrimitive()) {
                    errors.add("Requiredness.OPTIONAL on " + what + " of primitive type " + type
                            + " — a primitive always has a value");
                } else if (bv.required) {
                    errors.add("Requiredness.OPTIONAL on " + what + " contradicts its @NotNull, @NotBlank or"
                            + " @NotEmpty");
                }
                required = false;
            }
        }

        for (String error : errors) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR, PREFIX + error, element);
        }
        if (errors.isEmpty()) {
            for (PatternConstraint pattern : effective.patterns()) {
                PortableRegex.Translation translation = PortableRegex.translate(pattern);
                if (!translation.publishable()) {
                    env.getMessager().printMessage(Diagnostic.Kind.WARNING, PREFIX + "The pattern '"
                            + pattern.regex() + "' on " + what + " cannot be published in the OpenAPI and MCP"
                            + " schemas: " + translation.unsupported() + ". Bean Validation still enforces it",
                            element);
                }
            }
        }
        return new ParameterContract(effective, required, description);
    }

    // ------------------------------------------------------------ Bean Validation

    /** The Bean Validation constraints of one element, intersected as they are read (FR-003). */
    private static final class Accumulator {
        Endpoint lower;
        Endpoint upper;
        Integer minLength;
        Integer maxLength;
        Integer minItems;
        Integer maxItems;
        final Set<PatternConstraint> patterns = new TreeSet<>(EffectiveConstraints.PATTERN_ORDER);
        boolean notBlank;
        boolean required;

        void lower(Endpoint endpoint) {
            if (lower == null || endpoint.tighterLowerThan(lower)) {
                lower = endpoint;
            }
        }

        void upper(Endpoint endpoint) {
            if (upper == null || endpoint.tighterUpperThan(upper)) {
                upper = endpoint;
            }
        }

        EffectiveConstraints toConstraints() {
            return new EffectiveConstraints(lower == null ? null : EffectiveConstraints.decimal(lower.value()),
                    lower != null && lower.exclusive(),
                    upper == null ? null : EffectiveConstraints.decimal(upper.value()),
                    upper != null && upper.exclusive(), minLength, maxLength, minItems, maxItems,
                    List.copyOf(patterns), notBlank);
        }
    }

    private void readBeanValidation(String simple, AnnotationMirror mirror, ConstrainedType typeClass, Accumulator bv,
                                    String what, Set<String> errors) {
        Map<String, AnnotationValue> values = values(mirror);
        if (!inDefaultGroup(values.get(A_GROUPS))) {
            return;
        }
        switch (simple) {
            case NOT_NULL -> bv.required = true;
            case NOT_BLANK -> {
                bv.required = true;
                bv.notBlank = true;
            }
            case NOT_EMPTY -> {
                bv.required = true;
                if (typeClass.string()) {
                    bv.minLength = max(bv.minLength, 1);
                } else if (typeClass.items()) {
                    bv.minItems = max(bv.minItems, 1);
                }
            }
            case MIN -> bv.lower(new Endpoint(BigDecimal.valueOf(longValue(values.get(A_VALUE))), false));
            case MAX -> bv.upper(new Endpoint(BigDecimal.valueOf(longValue(values.get(A_VALUE))), false));
            case DECIMAL_MIN, DECIMAL_MAX -> {
                String text = (String) values.get(A_VALUE).getValue();
                BigDecimal value = decimal(text);
                if (value == null) {
                    errors.add("@" + simple + "(\"" + text + "\") on " + what + " is not a decimal number");
                    return;
                }
                Endpoint endpoint = new Endpoint(value, !(Boolean) values.get(A_INCLUSIVE).getValue());
                if (simple.equals(DECIMAL_MIN)) {
                    bv.lower(endpoint);
                } else {
                    bv.upper(endpoint);
                }
            }
            case POSITIVE -> bv.lower(new Endpoint(BigDecimal.ZERO, true));
            case POSITIVE_OR_ZERO -> bv.lower(new Endpoint(BigDecimal.ZERO, false));
            case NEGATIVE -> bv.upper(new Endpoint(BigDecimal.ZERO, true));
            case NEGATIVE_OR_ZERO -> bv.upper(new Endpoint(BigDecimal.ZERO, false));
            case SIZE -> {
                int min = (Integer) values.get(A_MIN).getValue();
                int max = (Integer) values.get(A_MAX).getValue();
                Integer recordedMin = min != 0 ? min : null;
                Integer recordedMax = max != Integer.MAX_VALUE ? max : null;
                if (typeClass.string()) {
                    bv.minLength = max(bv.minLength, recordedMin);
                    bv.maxLength = min(bv.maxLength, recordedMax);
                } else if (typeClass.items()) {
                    bv.minItems = max(bv.minItems, recordedMin);
                    bv.maxItems = min(bv.maxItems, recordedMax);
                }
            }
            case PATTERN -> {
                List<String> flags = new ArrayList<>();
                AnnotationValue flagValues = values.get(A_FLAGS);
                if (flagValues != null) {
                    for (Object flag : (List<?>) flagValues.getValue()) {
                        flags.add(((VariableElement) ((AnnotationValue) flag).getValue()).getSimpleName().toString());
                    }
                }
                bv.patterns.add(new PatternConstraint((String) values.get(A_REGEXP).getValue(), flags));
            }
            default -> {
            }
        }
    }

    private static boolean inDefaultGroup(AnnotationValue groups) {
        if (groups == null) {
            return true;
        }
        List<?> list = (List<?>) groups.getValue();
        if (list.isEmpty()) {
            return true;
        }
        for (Object group : list) {
            Object value = ((AnnotationValue) group).getValue();
            if (value instanceof DeclaredType declared
                    && ((TypeElement) declared.asElement()).getQualifiedName().contentEquals(DEFAULT_GROUP)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ @AgenticConstraints

    private EffectiveConstraints applyOverride(AgenticConstraints override, EffectiveConstraints bv,
                                               ConstrainedType typeClass, String what, boolean input,
                                               VariableElement element, Set<String> errors) {
        String minimum = bv.minimum();
        boolean exclusiveMinimum = bv.exclusiveMinimum();
        String maximum = bv.maximum();
        boolean exclusiveMaximum = bv.exclusiveMaximum();
        List<String> looser = new ArrayList<>();

        if (override.exclusiveMinimum() && override.minimum().isEmpty()) {
            errors.add("@AgenticConstraints(exclusiveMinimum = true) on " + what + " without a minimum");
        }
        if (override.exclusiveMaximum() && override.maximum().isEmpty()) {
            errors.add("@AgenticConstraints(exclusiveMaximum = true) on " + what + " without a maximum");
        }
        if (!override.minimum().isEmpty()) {
            BigDecimal value = decimal(override.minimum());
            if (value == null) {
                errors.add("@AgenticConstraints(minimum = \"" + override.minimum() + "\") on " + what
                        + " is not a decimal number");
            } else {
                Endpoint replacement = new Endpoint(value, override.exclusiveMinimum());
                Endpoint replaced = bv.lower();
                if (replaced != null && looserLower(replacement, replaced, typeClass.integral())) {
                    looser.add(K_MINIMUM + " " + replaced.renderLower() + " → " + replacement.renderLower());
                }
                minimum = EffectiveConstraints.decimal(value);
                exclusiveMinimum = override.exclusiveMinimum();
            }
        }
        if (!override.maximum().isEmpty()) {
            BigDecimal value = decimal(override.maximum());
            if (value == null) {
                errors.add("@AgenticConstraints(maximum = \"" + override.maximum() + "\") on " + what
                        + " is not a decimal number");
            } else {
                Endpoint replacement = new Endpoint(value, override.exclusiveMaximum());
                Endpoint replaced = bv.upper();
                if (replaced != null && looserUpper(replacement, replaced, typeClass.integral())) {
                    looser.add(K_MAXIMUM + " " + replaced.renderUpper() + " → " + replacement.renderUpper());
                }
                maximum = EffectiveConstraints.decimal(value);
                exclusiveMaximum = override.exclusiveMaximum();
            }
        }

        Integer minLength = replaceMin(K_MIN_LENGTH, bv.minLength(), override.minLength(), looser);
        Integer maxLength = replaceMax(K_MAX_LENGTH, bv.maxLength(), override.maxLength(), looser);
        Integer minItems = replaceMin(K_MIN_ITEMS, bv.minItems(), override.minItems(), looser);
        Integer maxItems = replaceMax(K_MAX_ITEMS, bv.maxItems(), override.maxItems(), looser);

        List<PatternConstraint> patterns = bv.patterns();
        if (!override.pattern().isEmpty()) {
            List<PatternConstraint> replacement = List.of(new PatternConstraint(override.pattern(), List.of()));
            if (!bv.patterns().isEmpty() && !bv.patterns().equals(replacement)) {
                looser.add(K_PATTERNS + " " + render(bv.patterns()) + " → " + render(replacement));
            }
            patterns = replacement;
        }

        if (input && !looser.isEmpty()) {
            for (String change : looser) {
                env.getMessager().printMessage(Diagnostic.Kind.WARNING, PREFIX + "@AgenticConstraints on " + what
                        + " is looser than its Bean Validation constraint: " + change + ". The MCP and OpenAPI"
                        + " contract accepts values the service's own validation rejects", element);
            }
        }
        return new EffectiveConstraints(minimum, exclusiveMinimum, maximum, exclusiveMaximum, minLength, maxLength,
                minItems, maxItems, patterns, bv.notBlank());
    }

    private static boolean looserLower(Endpoint replacement, Endpoint replaced, boolean integral) {
        return integral ? replaced.integralLower().tighterLowerThan(replacement.integralLower())
                : replaced.tighterLowerThan(replacement);
    }

    private static boolean looserUpper(Endpoint replacement, Endpoint replaced, boolean integral) {
        return integral ? replaced.integralUpper().tighterUpperThan(replacement.integralUpper())
                : replaced.tighterUpperThan(replacement);
    }

    private static Integer replaceMin(String key, Integer replaced, int override, List<String> looser) {
        if (override == UNSET) {
            return replaced;
        }
        if (replaced != null && override < replaced) {
            looser.add(key + " " + replaced + " → " + override);
        }
        return override;
    }

    private static Integer replaceMax(String key, Integer replaced, int override, List<String> looser) {
        if (override == UNSET) {
            return replaced;
        }
        if (replaced != null && override > replaced) {
            looser.add(key + " " + replaced + " → " + override);
        }
        return override;
    }

    private static String render(List<PatternConstraint> patterns) {
        return patterns.stream().map(p -> "'" + p.regex() + "'" + (p.flags().isEmpty() ? "" : " " + p.flags()))
                .toList().toString();
    }

    private static String qualifiedName(AnnotationMirror mirror) {
        return ((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().toString();
    }

    private Map<String, AnnotationValue> values(AnnotationMirror mirror) {
        Map<String, AnnotationValue> result = new HashMap<>();
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                : env.getElementUtils().getElementValuesWithDefaults(mirror).entrySet()) {
            result.put(entry.getKey().getSimpleName().toString(), entry.getValue());
        }
        return result;
    }

    private static List<AnnotationMirror> annotations(AnnotationValue value) {
        List<AnnotationMirror> result = new ArrayList<>();
        if (value != null) {
            for (Object item : (List<?>) value.getValue()) {
                result.add((AnnotationMirror) ((AnnotationValue) item).getValue());
            }
        }
        return result;
    }

    private static long longValue(AnnotationValue value) {
        return ((Number) value.getValue()).longValue();
    }

    private static BigDecimal decimal(String text) {
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer max(Integer current, Integer candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null ? candidate : Integer.valueOf(Math.max(current, candidate));
    }

    private static Integer min(Integer current, Integer candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null ? candidate : Integer.valueOf(Math.min(current, candidate));
    }
}
