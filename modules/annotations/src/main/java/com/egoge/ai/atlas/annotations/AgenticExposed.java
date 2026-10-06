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
 * Marks a service class or method for MCP tool and REST controller generation.
 * The annotation processor will generate:
 * <ul>
 *   <li>An MCP tool class with {@code @McpTool} annotation</li>
 *   <li>A REST controller with appropriate mappings</li>
 * </ul>
 *
 * <p>When applied to a type, all public methods are exposed.
 * When applied to a method, only that method is exposed.
 *
 * <p>Example usage:
 * <pre>{@code
 * @AgenticExposed(
 *     toolName = "getOrders",
 *     description = "Retrieves orders by status"
 * )
 * public OrderDto findByStatus(String status) { ... }
 * }</pre>
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AgenticExposed {

    /**
     * Name for the generated MCP tool. Defaults to the method/class name.
     */
    String toolName() default "";

    /**
     * Description of what this tool does and when to use it.
     * Used in MCP tool descriptions and OpenAPI operation summaries.
     */
    String description() default "";

    /**
     * The entity type returned by this method, used to resolve the
     * generated DTO for response mapping. Required when the return
     * type cannot be inferred from the method signature.
     *
     * <p>At compile time this is read via {@code MirroredTypeException}
     * to obtain the {@code TypeMirror}.
     */
    Class<?> returnType() default void.class;

    /**
     * Controls which channels this method is exposed to.
     * Defaults to {@code {Channel.INHERIT}}, which inherits the class-level
     * channels or the framework default ({@code AI, API}) if none are set.
     *
     * <p>When set explicitly, the values must be a subset of the class-level
     * channels (narrowing only). Mixing {@code INHERIT} with explicit values
     * is a compile error.
     *
     * <p>Use {@code channels = {Channel.API}} to expose only as a REST
     * endpoint, or {@code channels = {Channel.AI}} for MCP-only exposure.
     */
    Channel[] channels() default { Channel.INHERIT };

    /**
     * Exposure channel for generated service artifacts.
     */
    enum Channel {
        /** Inherit class-level channels, or framework default {AI, API} if no class-level value. */
        INHERIT,
        /** MCP tool generation */
        AI,
        /** REST controller and OpenAPI generation */
        API
    }

    /** Sentinel for int version attributes: "inherit from class, or use framework default." */
    int INHERIT = -1;

    /**
     * Minimum major API version where this method is available.
     * Included in generated artifacts when {@code apiSince <= configuredMajor}.
     * Use {@link #INHERIT} (default) to inherit from class-level annotation.
     * Framework default: 1 (available from the first version).
     */
    int apiSince() default INHERIT;

    /**
     * Maximum major API version where this method is available.
     * Excluded from generated artifacts when {@code configuredMajor > apiUntil}.
     * Use {@link #INHERIT} (default) to inherit from class-level annotation.
     * Framework default: {@code Integer.MAX_VALUE} (never removed).
     */
    int apiUntil() default INHERIT;

    /**
     * Major version at which this method became deprecated.
     * When {@code apiDeprecatedSince > 0 && apiDeprecatedSince <= configuredMajor},
     * the method is still generated but marked as deprecated in all artifacts.
     * Use {@link #INHERIT} (default) to inherit from class-level annotation.
     * Framework default: 0 (not deprecated).
     */
    int apiDeprecatedSince() default INHERIT;

    /** Sentinel for string version attributes: "inherit from class, or use framework default." */
    String INHERIT_STR = "\0";

    /**
     * Migration guidance for deprecated methods.
     * Only meaningful when {@code apiDeprecatedSince > 0}.
     * Use {@link #INHERIT_STR} (default) to inherit from class-level annotation.
     * Framework default: {@code ""} (no replacement).
     */
    String apiReplacement() default INHERIT_STR;

    /**
     * MCP hint: the tool does not modify its environment. A method-level value other than
     * {@link Hint#UNSET} overrides the class-level one; nothing is inferred. Hints are client
     * guidance, not authorization.
     */
    Hint readOnly() default Hint.UNSET;

    /**
     * MCP hint: the tool may perform destructive updates. Resolved like {@link #readOnly()}.
     */
    Hint destructive() default Hint.UNSET;

    /**
     * MCP hint: calling the tool again with the same arguments has no further effect. Resolved
     * like {@link #readOnly()}.
     */
    Hint idempotent() default Hint.UNSET;

    /**
     * MCP hint: the tool interacts with entities outside the service's own domain. Resolved like
     * {@link #readOnly()}.
     */
    Hint openWorld() default Hint.UNSET;

    /** The default of {@link #maxResults()}: no bound is declared. Writing it explicitly is a compile error. */
    int NO_MAX_RESULTS = -1;

    /**
     * The most elements the method's collection result holds, so exposing it without a paging
     * contract is safe; on a method taking a Spring Data {@code Pageable}, the largest page size a
     * client may request. Method-level only: a class-level value is a compile error. It must be at
     * least 1, on a method returning a collection, iterable, array, {@code Map}, or an
     * {@code Optional} of one. A {@code byte[]} is a binary payload, not a collection. On a
     * {@code Stream} it is a compile error unless the method takes a {@code Pageable}: a stream's
     * bound can be neither published nor counted.
     *
     * <p>The bound is declared, not enforced: generated wrappers never truncate a result, and the
     * runtime logs a WARN when a result holds more. A page size above the ceiling is rejected.
     * Read only with the processor option {@code ai.atlas.collections} on; declared with it off,
     * it is a compile error. Leaving it out declares none; any value written explicitly, including
     * {@link #NO_MAX_RESULTS}, is a declaration, and one below 1 is a compile error.
     */
    int maxResults() default NO_MAX_RESULTS;

    /**
     * The REST mapping of the operation. Requires the processor option {@code ai.atlas.rest=true};
     * without it, any value other than the default is a compile error, because the operation would
     * still be served at its RPC route.
     *
     * <p>The default, an empty {@code @Rest}, keeps the RPC mapping: {@code GET} without
     * parameters, {@code POST} with them, at {@code /<service-kebab>/<method-kebab>}, every
     * argument a query parameter and every success a 200. On a method, {@code @Rest} may set only
     * {@link Rest#method()}, {@link Rest#path()} and {@link Rest#status()}; on a class, only
     * {@link Rest#style()} and {@link Rest#resource()}. Each attribute a method declares wins over
     * the CRUD convention, attribute by attribute.
     *
     * <p>Example usage:
     * <pre>{@code
     * @AgenticExposed(rest = @Rest(style = RestStyle.CRUD, resource = "orders"))
     * public class OrderService {
     *     public Order findById(Long id) { ... }                    // GET /orders/{id}
     *
     *     @AgenticExposed(rest = @Rest(method = HttpMethod.PATCH, path = "/{id}/status"))
     *     public Order changeStatus(Long id, String status) { ... } // id in the path, status in the query
     * }
     * }</pre>
     */
    Rest rest() default @Rest;

    /** The HTTP method of a REST mapping. */
    enum HttpMethod {
        /** Not declared: the CRUD rule the method matches, else GET without parameters and POST with them. */
        UNSET,
        /** {@code GET}. */
        GET,
        /** {@code POST}. */
        POST,
        /** {@code PUT}. */
        PUT,
        /** {@code PATCH}. */
        PATCH,
        /** {@code DELETE}. */
        DELETE
    }

    /** How a service maps the operations that declare no explicit REST mapping. */
    enum RestStyle {
        /** Not declared: {@link #RPC}. */
        INHERIT,
        /** {@code /<method-kebab>} below the resource, GET without parameters and POST with them. */
        RPC,
        /**
         * The five documented CRUD rules for the methods whose name and parameters match one, and
         * {@link #RPC} for every other method.
         */
        CRUD
    }

    /**
     * REST metadata of an operation or, for {@link #style()} and {@link #resource()}, of a service.
     * Every attribute's default means "not declared".
     */
    @Documented
    @Target({})
    @Retention(RetentionPolicy.RUNTIME)
    @interface Rest {

        /** Method level only: the HTTP method. {@link HttpMethod#UNSET} (default) derives it. */
        HttpMethod method() default HttpMethod.UNSET;

        /**
         * Method level only: the path below the service's resource, such as {@code "/{id}"} or
         * {@code "/{id}/items"}; {@code ""} is the resource itself. Each segment is a literal of
         * letters, digits, {@code .}, {@code _}, {@code ~} or {@code -}, or a {@code {name}}
         * variable binding the method parameter of that name. The default, a single NUL
         * character, derives the path.
         */
        String path() default "\0";

        /**
         * Method level only: the success status, a 2xx code Spring's {@code HttpStatus} names.
         * {@code 0} (default) derives it: the CRUD rule's status, else 204 for a {@code void}
         * DELETE, else 200.
         */
        int status() default 0;

        /** Class level only: how the service's undeclared operations are mapped. */
        RestStyle style() default RestStyle.INHERIT;

        /**
         * Class level only: the resource, one path segment every operation of the service is
         * mapped below. Empty (default) keeps the service's kebab-case name, as the RPC mapping
         * has it; nothing is pluralized.
         */
        String resource() default "";
    }
}
