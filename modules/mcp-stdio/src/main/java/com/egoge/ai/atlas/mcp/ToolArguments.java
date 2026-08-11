/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.mcp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses and validates the tool-call arguments of {@link AtlasMcpServer}'s tools. Exactly the
 * advertised input schema is accepted: nothing is coerced, unknown keys are rejected, and every
 * violation throws {@link IllegalArgumentException} with a message that doubles as the in-band
 * error result's summary.
 */
final class ToolArguments {

    private ToolArguments() {
    }

    /**
     * Enforces the advertised {@code additionalProperties: false}: any top-level argument key
     * outside the tool's schema is rejected before dispatch, so a typo fails loudly instead of
     * being silently ignored and producing a misleadingly successful result.
     */
    static void rejectUnknownArguments(Map<String, Object> arguments, boolean withOut) {
        for (String key : arguments.keySet()) {
            boolean known = AtlasMcpServer.ARG_SOURCES.equals(key)
                    || AtlasMcpServer.ARG_CLASSPATH.equals(key)
                    || AtlasMcpServer.ARG_OPTIONS.equals(key)
                    || (withOut && AtlasMcpServer.ARG_OUT.equals(key));
            if (!known) {
                throw new IllegalArgumentException("unknown argument '" + key + "'; this tool "
                        + "accepts no properties outside its input schema");
            }
        }
    }

    /**
     * The {@code sources} argument as paths; existence is validated by the generator. Exactly the
     * advertised schema is accepted — an array of strings. A scalar or a non-string element is
     * rejected rather than coerced, so a malformed call fails loudly instead of compiling from
     * a path like "123".
     */
    static List<Path> sources(Map<String, Object> arguments) {
        if (!(arguments.get(AtlasMcpServer.ARG_SOURCES) instanceof Collection<?> collection)) {
            throw new IllegalArgumentException("'" + AtlasMcpServer.ARG_SOURCES
                    + "' is required and must be an array of path strings");
        }
        List<Path> sources = new ArrayList<>();
        for (Object element : collection) {
            if (!(element instanceof String value)) {
                throw new IllegalArgumentException("'" + AtlasMcpServer.ARG_SOURCES
                        + "' entries must be strings, got: " + typeName(element));
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                sources.add(Path.of(trimmed));
            }
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException(
                    "'" + AtlasMcpServer.ARG_SOURCES + "' must not be empty");
        }
        return List.copyOf(sources);
    }

    /**
     * The required {@code classpath} argument split on the platform path separator, blank entries
     * dropped. It is required (FR-005): the generated wrappers reference Spring AI and Spring Web
     * types, so a call without a classpath cannot produce compilable output. Every surviving
     * entry is checked here, at the boundary: javac silently ignores a classpath entry it cannot
     * read, so a typo would otherwise surface as a wall of missing-symbol errors.
     */
    static List<Path> classpath(Map<String, Object> arguments) {
        if (!(arguments.get(AtlasMcpServer.ARG_CLASSPATH) instanceof String value)
                || value.isBlank()) {
            throw new IllegalArgumentException("'" + AtlasMcpServer.ARG_CLASSPATH
                    + "' is required and must be one non-blank path-separator-separated string "
                    + "carrying the Spring AI and Spring Web types the generated wrappers "
                    + "reference");
        }
        List<Path> entries = new ArrayList<>();
        for (String entry : value.split(File.pathSeparator, -1)) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path path = Path.of(trimmed);
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("classpath entry does not exist: " + path);
            }
            if (!Files.isReadable(path)) {
                throw new IllegalArgumentException("classpath entry is not readable: " + path);
            }
            entries.add(path);
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "'" + AtlasMcpServer.ARG_CLASSPATH + "' must carry at least one entry");
        }
        return entries;
    }

    /** The required {@code out} argument of {@value AtlasMcpServer#TOOL_GENERATE}. */
    static Path outputDir(Map<String, Object> arguments) {
        if (!(arguments.get(AtlasMcpServer.ARG_OUT) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(
                    "'" + AtlasMcpServer.ARG_OUT + "' is required and must be a directory path");
        }
        return Path.of(value.trim());
    }

    /**
     * The optional {@code options} argument as processor {@code -A} options. Non-string keys or
     * values are rejected rather than coerced: a caller passing {@code {"ai.atlas.api.major": 2}}
     * almost meant {@code "2"}, but silently stringifying would also accept arrays and objects
     * the processor could never have been handed by a real build. Only an absent key means
     * "no options" — an explicit {@code options: null} violates the schema (which declares an
     * object) and is rejected, not treated as an omission.
     */
    static Map<String, String> options(Map<String, Object> arguments) {
        if (!arguments.containsKey(AtlasMcpServer.ARG_OPTIONS)) {
            return Map.of();
        }
        Object raw = arguments.get(AtlasMcpServer.ARG_OPTIONS);
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("'" + AtlasMcpServer.ARG_OPTIONS + "' must be an "
                    + "object of string-to-string pairs, got: " + typeName(raw));
        }
        Map<String, String> options = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (!(key instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException(
                        "'" + AtlasMcpServer.ARG_OPTIONS + "' keys must be non-blank strings");
            }
            if (!(value instanceof String option)) {
                throw new IllegalArgumentException("'" + AtlasMcpServer.ARG_OPTIONS + "' values "
                        + "must be strings; '" + name + "' is " + typeName(value));
            }
            options.put(name, option);
        });
        return options;
    }

    /** The plain type name of a rejected argument value, for error messages; handles null. */
    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
