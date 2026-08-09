/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.cli;

import com.egoge.ai.atlas.processor.driver.Diagnostic;
import com.egoge.ai.atlas.processor.driver.GeneratedFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;

/**
 * Builds the stable JSON document the CLI writes to stdout under {@code --json}.
 *
 * <h2>Schema (version {@value #SCHEMA_VERSION})</h2>
 *
 * <p>Every key below is <em>always present</em>, on success and on failure alike, so a hook can
 * read a field without probing for it first. Keys are emitted in the order shown. Values that do
 * not apply to a command are {@code null} (objects/strings) or empty (arrays) — never omitted.
 *
 * <pre>{@code
 * {
 *   "schemaVersion": 1,                  // int, bumped only on a breaking change
 *   "command": "generate",               // "generate" | "openapi" | "inspect"
 *   "status": "ok",                      // "ok" | "error" — mirrors the process exit code
 *   "outputDir": "/abs/out",             // generate: the directory written; openapi/inspect: null
 *   "files": [                           // manifest of what this run emitted, [] on failure
 *     {
 *       "kind": "DTO",                   // GeneratedFile.Kind name
 *       "relativePath": "app/FooDto.java",
 *       "path": "/abs/out/sources/app/FooDto.java",
 *       "source": true                   // true = generated Java source, false = resource
 *     }
 *   ],
 *   "counts": { "DTO": 4, "MCP_TOOL": 1 },   // per-kind totals, kinds with 0 omitted
 *   "openApi": {                             // null unless an OpenAPI document was produced
 *     "path": "/abs/openapi.json",           // where it was written, or null if not written
 *     "document": "{ ... }"                  // the spec text
 *   },
 *   "diagnostics": [                     // every compiler / processor message, javac's order
 *     { "severity": "WARNING", "message": "...", "source": "/abs/Foo.java" }
 *   ],
 *   "errors": [ "..." ],                 // human-readable failure reasons, [] when status is ok
 *   "services": [                        // qualified names of the @AgenticExposed service classes
 *     "com.example.MyService"            //   discovered in the source set; [] on failure, and
 *   ]                                    //   [] for non-inspect commands — always an array
 * }
 * }</pre>
 *
 * <p>Stability contract: within a {@code schemaVersion}, keys and value types are only ever added,
 * never removed or retyped. Consumers must ignore unknown keys.
 */
public final class JsonOutput {

    /** Version of the document shape described in this class's documentation. */
    public static final int SCHEMA_VERSION = 1;

    /** Value of {@code status} for a run that completed without errors. */
    public static final String STATUS_OK = "ok";
    /** Value of {@code status} for a run that failed. */
    public static final String STATUS_ERROR = "error";

    private static final String KEY_SCHEMA_VERSION = "schemaVersion";
    private static final String KEY_COMMAND = "command";
    private static final String KEY_STATUS = "status";
    private static final String KEY_OUTPUT_DIR = "outputDir";
    private static final String KEY_FILES = "files";
    private static final String KEY_COUNTS = "counts";
    private static final String KEY_OPEN_API = "openApi";
    private static final String KEY_DIAGNOSTICS = "diagnostics";
    private static final String KEY_ERRORS = "errors";
    private static final String KEY_SERVICES = "services";

    private static final String KEY_KIND = "kind";
    private static final String KEY_RELATIVE_PATH = "relativePath";
    private static final String KEY_PATH = "path";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_DOCUMENT = "document";
    private static final String KEY_SEVERITY = "severity";
    private static final String KEY_MESSAGE = "message";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectNode root;
    private final ArrayNode errors;

    private JsonOutput(String command) {
        this.root = MAPPER.createObjectNode();
        // Written up front so the key order is fixed regardless of which setters a command calls.
        root.put(KEY_SCHEMA_VERSION, SCHEMA_VERSION);
        root.put(KEY_COMMAND, command);
        root.put(KEY_STATUS, STATUS_ERROR);
        root.putNull(KEY_OUTPUT_DIR);
        root.putArray(KEY_FILES);
        root.putObject(KEY_COUNTS);
        root.putNull(KEY_OPEN_API);
        root.putArray(KEY_DIAGNOSTICS);
        this.errors = root.putArray(KEY_ERRORS);
        root.putArray(KEY_SERVICES);
    }

    /**
     * Starts a document for the given subcommand. It is a complete, valid failure report as-is —
     * a command that dies before producing anything still renders the full schema.
     *
     * @param command the {@code command} value, e.g. {@code "generate"}
     * @return a new builder
     */
    public static JsonOutput forCommand(String command) {
        return new JsonOutput(command);
    }

    /** Sets {@code status} to {@value #STATUS_OK} or {@value #STATUS_ERROR}. */
    public JsonOutput status(boolean ok) {
        root.put(KEY_STATUS, ok ? STATUS_OK : STATUS_ERROR);
        return this;
    }

    /** Sets {@code outputDir} to the directory the run wrote into. */
    public JsonOutput outputDir(Path dir) {
        root.put(KEY_OUTPUT_DIR, dir.toString());
        return this;
    }

    /** Fills {@code files} with the run's manifest and {@code counts} with its per-kind totals. */
    public JsonOutput files(List<GeneratedFile> files) {
        ArrayNode array = root.putArray(KEY_FILES);
        ObjectNode counts = root.putObject(KEY_COUNTS);
        for (GeneratedFile file : files) {
            ObjectNode node = array.addObject();
            node.put(KEY_KIND, file.kind().name());
            node.put(KEY_RELATIVE_PATH, file.relativePath());
            node.put(KEY_PATH, file.path().toString());
            node.put(KEY_SOURCE, file.kind().isSource());
            String kind = file.kind().name();
            counts.put(kind, counts.path(kind).asInt() + 1);
        }
        return this;
    }

    /**
     * Like {@link #files(List)} but sets each absolute {@code path} to {@code null} — the files
     * were collected from a throwaway run and were never written to a caller-visible directory.
     */
    public JsonOutput inspectFiles(List<GeneratedFile> files) {
        ArrayNode array = root.putArray(KEY_FILES);
        ObjectNode counts = root.putObject(KEY_COUNTS);
        for (GeneratedFile file : files) {
            ObjectNode node = array.addObject();
            node.put(KEY_KIND, file.kind().name());
            node.put(KEY_RELATIVE_PATH, file.relativePath());
            node.putNull(KEY_PATH);
            node.put(KEY_SOURCE, file.kind().isSource());
            String kind = file.kind().name();
            counts.put(kind, counts.path(kind).asInt() + 1);
        }
        return this;
    }

    /**
     * Sets the {@code services} array — the simple names of the {@code @AgenticExposed} service
     * classes discovered in the source set. Only populated by the {@code inspect} command.
     */
    public JsonOutput inspectServices(List<String> services) {
        ArrayNode array = root.putArray(KEY_SERVICES);
        for (String service : services) {
            array.add(service);
        }
        return this;
    }

    /**
     * Sets the {@code openApi} object.
     *
     * @param document the spec text; a {@code null} leaves {@code openApi} null
     * @param writtenTo where the document was written, or {@code null} when it was not written
     */
    public JsonOutput openApi(String document, Path writtenTo) {
        if (document == null) {
            root.putNull(KEY_OPEN_API);
            return this;
        }
        ObjectNode node = root.putObject(KEY_OPEN_API);
        if (writtenTo == null) {
            node.putNull(KEY_PATH);
        } else {
            node.put(KEY_PATH, writtenTo.toString());
        }
        node.put(KEY_DOCUMENT, document);
        return this;
    }

    /** Fills {@code diagnostics} with every message the compiler and processor reported. */
    public JsonOutput diagnostics(List<Diagnostic> diagnostics) {
        ArrayNode array = root.putArray(KEY_DIAGNOSTICS);
        for (Diagnostic diagnostic : diagnostics) {
            ObjectNode node = array.addObject();
            node.put(KEY_SEVERITY, diagnostic.severity().name());
            node.put(KEY_MESSAGE, diagnostic.message());
            node.put(KEY_SOURCE, diagnostic.source());
        }
        return this;
    }

    /** Appends a human-readable failure reason to {@code errors}. */
    public JsonOutput error(String message) {
        errors.add(message);
        return this;
    }

    /**
     * Renders the document as a single line of JSON.
     *
     * <p>One line keeps the output greppable and safe to pipe into {@code jq} line-by-line; the
     * schema, not the whitespace, is the contract.
     *
     * @return the JSON text, without a trailing newline
     */
    public String render() {
        return root.toString();
    }
}
