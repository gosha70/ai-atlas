/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides the constraints ai-atlas reads from Jakarta Bean Validation on an
 * {@link AgenticField} field or a parameter of an {@link AgenticExposed} method.
 *
 * <p>Each attribute set here replaces the Bean Validation value of the same key; a non-empty
 * {@link #pattern()} replaces every Bean Validation pattern. Every attribute left at its default
 * keeps the Bean Validation value. An override on a parameter that is looser than the Bean
 * Validation constraint it replaces is reported as a compile warning.
 *
 * <p>Example usage:
 * <pre>{@code
 * public List<Order> find(@AgenticConstraints(minimum = "1", maximum = "100") int limit) { ... }
 * }</pre>
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface AgenticConstraints {

    /** Lower bound, a decimal string such as {@code "0"} or {@code "0.5"}. Empty (default) means not set. */
    String minimum() default "";

    /** Whether {@link #minimum()} is exclusive. Requires {@link #minimum()}. */
    boolean exclusiveMinimum() default false;

    /** Upper bound, a decimal string. Empty (default) means not set. */
    String maximum() default "";

    /** Whether {@link #maximum()} is exclusive. Requires {@link #maximum()}. */
    boolean exclusiveMaximum() default false;

    /** Minimum length of a string. {@code -1} (default) means not set. */
    int minLength() default -1;

    /** Maximum length of a string. {@code -1} (default) means not set. */
    int maxLength() default -1;

    /** Minimum number of items of a collection or array. {@code -1} (default) means not set. */
    int minItems() default -1;

    /** Maximum number of items of a collection or array. {@code -1} (default) means not set. */
    int maxItems() default -1;

    /**
     * A Java regular expression the <em>whole</em> value must match, as Bean Validation's
     * {@code @Pattern}. Empty (default) means not set.
     */
    String pattern() default "";
}
