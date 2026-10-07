/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.processor.constraints.ConstrainedType;
import com.egoge.ai.atlas.processor.constraints.EffectiveConstraints;
import com.egoge.ai.atlas.processor.constraints.EffectiveConstraints.PatternConstraint;
import com.egoge.ai.atlas.processor.constraints.PortableRegex;
import com.egoge.ai.atlas.processor.contract.ContractIr.Field;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;
import com.egoge.ai.atlas.processor.contract.ContractIr.Parameter;
import com.egoge.ai.atlas.processor.contract.ContractProjection;
import com.egoge.ai.atlas.processor.model.EntityModel;
import com.egoge.ai.atlas.processor.model.FieldModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.ParameterModel;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.WildcardTypeName;
import io.swagger.v3.oas.models.media.Schema;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What {@code ai.atlas.constraints} adds to the generated surfaces (FR-014..017): the projection
 * the effective constraints, requiredness and hints are read from, and whether the Jakarta Bean
 * Validation API resolves in the compilation. Generators receive {@code null} when the flag is off,
 * and then generate exactly what they did before this feature (FR-012).
 *
 * <p>Also renders the IR's normalised constraints into the two schema dialects (ADR-7): OpenAPI 3.0
 * (boolean exclusives) and JSON Schema 2020-12 (numeric exclusives replacing the bound). Only
 * publishable patterns are written (FR-004a); several are written as an {@code allOf} of
 * single-{@code pattern} schemas inside the property.
 *
 * @param projection     the projection the generators consume
 * @param beanValidation whether {@code jakarta.validation.constraints.NotNull} resolves
 * @param env            the processing environment, which resolves parameter types
 */
public record ConstraintSurfaces(ContractProjection projection, boolean beanValidation, ProcessingEnvironment env) {

    static final String K_MINIMUM = "minimum";
    static final String K_EXCLUSIVE_MINIMUM = "exclusiveMinimum";
    static final String K_MAXIMUM = "maximum";
    static final String K_EXCLUSIVE_MAXIMUM = "exclusiveMaximum";
    static final String K_MIN_LENGTH = "minLength";
    static final String K_MAX_LENGTH = "maxLength";
    static final String K_MIN_ITEMS = "minItems";
    static final String K_MAX_ITEMS = "maxItems";
    static final String K_PATTERN = "pattern";
    static final String K_ALL_OF = "allOf";

    /**
     * @param operationKey the operation's IR identity, {@link ContractProjection#operationKey}
     * @return the IR operation, with its parameters' contracts and its hints
     * @throws IllegalStateException when the projection has no such active operation
     */
    Operation operation(String operationKey) {
        Operation operation = projection.operation(operationKey);
        if (operation == null) {
            throw new IllegalStateException("No IR operation projected for " + operationKey);
        }
        return operation;
    }

    /**
     * @param operation the IR operation of the method declaring {@code param}
     * @param index     {@code param}'s position in the method's parameters
     * @param param     the generator's parameter
     * @return the IR parameter at {@code index}
     * @throws IllegalStateException when the IR has no parameter of that name at {@code index}
     */
    static Parameter parameter(Operation operation, int index, ParameterModel param) {
        List<Parameter> parameters = operation.parameters();
        if (index >= parameters.size() || !parameters.get(index).name().equals(param.name())) {
            throw new IllegalStateException("IR operation " + operation.id() + " has no parameter '"
                    + param.name() + "' at position " + index);
        }
        return parameters.get(index);
    }

    /**
     * Whether a parameter of {@code type} is a JSON array: an array or any {@code java.util.Collection}
     * subtype, the test {@link ConstrainedType} applies to item counts (FR-002).
     *
     * @param type the parameter's type, raw or parameterized
     * @return whether it is an array or a collection
     */
    boolean collection(TypeName type) {
        return collection(type, env);
    }

    /**
     * Whether a parameter of {@code type} is a JSON array, as {@link #collection(TypeName)} decides,
     * with or without {@code ai.atlas.constraints}.
     *
     * @param type the parameter's type, raw or parameterized
     * @param env  the processing environment
     * @return whether it is an array or a collection
     */
    static boolean collection(TypeName type, ProcessingEnvironment env) {
        if (type instanceof ArrayTypeName) {
            return true;
        }
        TypeName raw = type instanceof ParameterizedTypeName parameterized ? parameterized.rawType() : type;
        if (!(raw instanceof ClassName className)) {
            return false;
        }
        TypeElement element = env.getElementUtils().getTypeElement(className.canonicalName());
        return element != null && ConstrainedType.of(element.asType(), env).items();
    }

    /**
     * The element type of an array, or of a collection with one type argument, a wildcard read as
     * its upper bound; {@code null} when raw.
     */
    static TypeName elementType(TypeName type) {
        if (type instanceof ArrayTypeName array) {
            return array.componentType();
        }
        if (type instanceof ParameterizedTypeName parameterized && parameterized.typeArguments().size() == 1) {
            TypeName argument = parameterized.typeArguments().get(0);
            return argument instanceof WildcardTypeName wildcard ? wildcard.upperBounds().get(0) : argument;
        }
        return null;
    }

    /** The effective constraints of a projected DTO field. */
    EffectiveConstraints field(EntityModel entity, FieldModel field) {
        Field irField = projection.field(entity.sourceClassName().canonicalName(), field.name());
        return irField != null ? irField.constraints() : EffectiveConstraints.NONE;
    }

    /**
     * The patterns a schema publishes: each publishable one translated and anchored, in the IR's
     * order, then {@code notBlank}'s unanchored pattern (FR-004a).
     */
    static List<String> publishedPatterns(EffectiveConstraints c) {
        List<String> published = new ArrayList<>();
        for (PatternConstraint pattern : c.patterns()) {
            PortableRegex.Translation translation = PortableRegex.translate(pattern);
            if (translation.publishable()) {
                published.add(translation.published());
            }
        }
        if (c.notBlank()) {
            published.add(PortableRegex.NOT_BLANK_PATTERN);
        }
        return published;
    }

    /** {@code minLength}, raised to 1 by {@code notBlank}. */
    static Integer minLength(EffectiveConstraints c) {
        if (!c.notBlank()) {
            return c.minLength();
        }
        return c.minLength() == null ? 1 : Math.max(c.minLength(), 1);
    }

    /** Writes {@code c} onto {@code schema} in the OpenAPI 3.0 Schema Object dialect (FR-014). */
    @SuppressWarnings("rawtypes") // swagger-models allOf() takes raw Schema elements
    static void applyOpenApi(Schema<?> schema, EffectiveConstraints c) {
        if (c.minimum() != null) {
            schema.minimum(new BigDecimal(c.minimum()));
            if (c.exclusiveMinimum()) {
                schema.exclusiveMinimum(true);
            }
        }
        if (c.maximum() != null) {
            schema.maximum(new BigDecimal(c.maximum()));
            if (c.exclusiveMaximum()) {
                schema.exclusiveMaximum(true);
            }
        }
        Integer minLength = minLength(c);
        if (minLength != null) {
            schema.minLength(minLength);
        }
        if (c.maxLength() != null) {
            schema.maxLength(c.maxLength());
        }
        if (c.minItems() != null) {
            schema.minItems(c.minItems());
        }
        if (c.maxItems() != null) {
            schema.maxItems(c.maxItems());
        }
        List<String> patterns = publishedPatterns(c);
        if (patterns.size() == 1) {
            schema.pattern(patterns.get(0));
        } else if (patterns.size() > 1) {
            List<Schema> allOf = new ArrayList<>();
            patterns.forEach(pattern -> allOf.add(new Schema<>().pattern(pattern)));
            schema.allOf(allOf);
        }
    }

    /**
     * Writes {@code c} into a property in the JSON Schema 2020-12 dialect (FR-017): an exclusive
     * bound is the numeric {@code exclusiveMinimum}/{@code exclusiveMaximum} instead of
     * {@code minimum}/{@code maximum}.
     */
    static void applyJsonSchema(Map<String, Object> property, EffectiveConstraints c) {
        if (c.minimum() != null) {
            property.put(c.exclusiveMinimum() ? K_EXCLUSIVE_MINIMUM : K_MINIMUM, new BigDecimal(c.minimum()));
        }
        if (c.maximum() != null) {
            property.put(c.exclusiveMaximum() ? K_EXCLUSIVE_MAXIMUM : K_MAXIMUM, new BigDecimal(c.maximum()));
        }
        putIfSet(property, K_MIN_LENGTH, minLength(c));
        putIfSet(property, K_MAX_LENGTH, c.maxLength());
        putIfSet(property, K_MIN_ITEMS, c.minItems());
        putIfSet(property, K_MAX_ITEMS, c.maxItems());
        List<String> patterns = publishedPatterns(c);
        if (patterns.size() == 1) {
            property.put(K_PATTERN, patterns.get(0));
        } else if (patterns.size() > 1) {
            List<Object> allOf = new ArrayList<>();
            for (String pattern : patterns) {
                Map<String, Object> single = new LinkedHashMap<>();
                single.put(K_PATTERN, pattern);
                allOf.add(single);
            }
            property.put(K_ALL_OF, allOf);
        }
    }

    private static void putIfSet(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
