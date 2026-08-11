/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.mcp;

import com.egoge.ai.atlas.processor.driver.AtlasGenerator;
import com.egoge.ai.atlas.processor.driver.Diagnostic;
import com.egoge.ai.atlas.processor.driver.GeneratedFile;
import com.egoge.ai.atlas.processor.driver.GenerationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Standalone STDIO MCP server exposing ai-atlas generation to AI harnesses (US4).
 *
 * <p>A copilot registers the packaged {@code atlas-mcp.jar} in {@code .mcp.json} as
 * {@code {"command":"java","args":["-jar","atlas-mcp.jar"]}} and this process speaks MCP over
 * stdin/stdout — no Spring Boot application, no network. It is a thin wrapper over
 * {@link AtlasGenerator}, the same driver the {@code atlas} CLI uses, so the emitted artifacts are
 * byte-identical to the annotation-processing build.
 *
 * <p>Every tool result carries a single JSON text document with a {@code summary} line and a
 * {@code files} manifest (FR-007), mirroring the CLI's {@code --json} schema, so an agent can
 * review what was (or would be) generated before writing anything into a workspace. stdout is
 * reserved for the MCP protocol; this class never prints to it.
 */
public final class AtlasMcpServer {

    /** The MCP server name reported during initialization. */
    public static final String SERVER_NAME = "ai-atlas";

    /** Dry-run tool: reports discovered services and would-be artifacts, writes nothing. */
    public static final String TOOL_INSPECT = "atlas_inspect_services";
    /** Generation tool: writes generated sources/resources under the given output directory. */
    public static final String TOOL_GENERATE = "atlas_generate";
    /** OpenAPI tool: returns just the OpenAPI document, produced in-memory. */
    public static final String TOOL_OPENAPI = "atlas_openapi";

    /** Tool argument: source roots or {@code .java} files to generate from. */
    public static final String ARG_SOURCES = "sources";
    /** Tool argument: compile classpath for the sources, path-separator-separated. */
    public static final String ARG_CLASSPATH = "classpath";
    /** Tool argument ({@value TOOL_GENERATE} only): directory to write generated output into. */
    public static final String ARG_OUT = "out";
    /** Tool argument: annotation-processor {@code -A} options as a string-to-string object. */
    public static final String ARG_OPTIONS = "options";

    /** Value of {@code status} in a tool result whose run completed without errors. */
    public static final String STATUS_OK = "ok";
    /** Value of {@code status} in a tool result whose run failed. */
    public static final String STATUS_ERROR = "error";

    private static final String DEV_VERSION = "development";
    // Semantic failures: the compile succeeded but the tool could not deliver what it promises.
    // Full sentences, because they double as the result's summary line (the review headline).
    private static final String NO_SPEC =
            "The sources compiled, but no OpenAPI document was generated — are any types "
                    + "annotated with @AgenticExposed?";
    private static final String NOTHING_TO_GENERATE =
            "The sources compiled but produced nothing to generate — are any types annotated "
                    + "with @AgenticExposed or @AgenticEntity? Any previously generated output "
                    + "under '" + ARG_OUT + "' was left as it was.";
    private static final String NOTHING_TO_INSPECT =
            "The sources compiled but produced nothing to inspect — are any types annotated "
                    + "with @AgenticExposed or @AgenticEntity?";
    private static final String GENERATION_FAILED = "generation failed; no output was written.";

    // Result document keys — the CLI's JsonOutput schema, with "tool" and "summary" added and no
    // schemaVersion/command. Within a key the value type never changes; consumers must ignore
    // unknown keys.
    private static final String KEY_TOOL = "tool";
    private static final String KEY_STATUS = "status";
    private static final String KEY_SUMMARY = "summary";
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

    // JSON Schema vocabulary for the tools' input schemas.
    private static final String TYPE_OBJECT = "object";
    private static final String SCHEMA_TYPE = "type";
    private static final String SCHEMA_ITEMS = "items";
    private static final String SCHEMA_DESCRIPTION = "description";
    private static final String SCHEMA_ADDITIONAL_PROPERTIES = "additionalProperties";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AtlasMcpServer() {
    }

    /**
     * Entry point of {@code atlas-mcp.jar}: wires the three tools to a stdio transport and
     * returns. The transport's single-thread executors are non-daemon, so the process stays alive
     * serving requests until the client closes the stdio streams or kills it.
     *
     * @param args ignored — configuration travels per-call in the tool arguments
     */
    public static void main(String[] args) {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(MAPPER);
        McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(jsonMapper))
                .serverInfo(SERVER_NAME, version())
                // tools(false): the tool list is static, so listChanged notifications are
                // neither supported nor advertised.
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(inspectTool(), generateTool(), openApiTool())
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully, "atlas-mcp-shutdown"));
    }

    /** The version stamped into the jar manifest at build time, or a development fallback. */
    static String version() {
        String version = AtlasMcpServer.class.getPackage().getImplementationVersion();
        return version == null ? DEV_VERSION : version;
    }

    private static SyncToolSpecification inspectTool() {
        return tool(TOOL_INSPECT,
                "Dry run: compiles the given @AgenticExposed-annotated Java sources with the "
                        + "ai-atlas annotation processor entirely in-memory and reports the "
                        + "services discovered plus every artifact that WOULD be generated "
                        + "(PII-safe DTOs, MCP tools, REST controllers, OpenAPI spec) — nothing "
                        + "is written to disk. Use this first, to review what generation would "
                        + "produce before calling " + TOOL_GENERATE + ".",
                false,
                arguments -> {
                    GenerationResult result = AtlasGenerator.generateInspect(
                            ToolArguments.sources(arguments), ToolArguments.classpath(arguments),
                            ToolArguments.options(arguments));
                    // A discovered service with no emitted file is still something to inspect —
                    // only "no files AND no services" means there was nothing (as in the CLI).
                    String failure = result.success() && result.files().isEmpty()
                            && result.discoveredServices().isEmpty() ? NOTHING_TO_INSPECT : null;
                    return document(TOOL_INSPECT, result, failure);
                });
    }

    private static SyncToolSpecification generateTool() {
        return tool(TOOL_GENERATE,
                "Runs ai-atlas generation and writes the generated sources and resources under "
                        + "the '" + ARG_OUT + "' directory (in '" + AtlasGenerator.SOURCES_DIR
                        + "' and '" + AtlasGenerator.RESOURCES_DIR + "' roots). Returns the "
                        + "file manifest and a summary for review. Use after "
                        + TOOL_INSPECT + " confirms the source set looks right; output is "
                        + "byte-identical to the annotation-processing build.",
                true,
                arguments -> {
                    GenerationResult result = AtlasGenerator.generate(
                            ToolArguments.sources(arguments), ToolArguments.classpath(arguments),
                            ToolArguments.outputDir(arguments), ToolArguments.options(arguments));
                    // A clean compile that emitted no files is the documented "nothing to
                    // generate" failure (as in the CLI): the driver published nothing, so any
                    // previous output under 'out' must not be mistaken for this run's.
                    String failure = result.success() && result.files().isEmpty()
                            ? NOTHING_TO_GENERATE : null;
                    return document(TOOL_GENERATE, result, failure);
                });
    }

    private static SyncToolSpecification openApiTool() {
        return tool(TOOL_OPENAPI,
                "Produces just the OpenAPI 3 specification for the given sources, in-memory — "
                        + "nothing is written to disk. The document is returned in the result's "
                        + "'" + KEY_OPEN_API + "." + KEY_DOCUMENT + "' field for review. Use when "
                        + "only the API contract is needed, e.g. to hand to a client generator.",
                false,
                arguments -> {
                    GenerationResult result = AtlasGenerator.generateInspect(
                            ToolArguments.sources(arguments), ToolArguments.classpath(arguments),
                            ToolArguments.options(arguments));
                    String failure = result.success() && result.openApi() == null ? NO_SPEC : null;
                    return document(TOOL_OPENAPI, result, failure);
                });
    }

    /** Builds one tool specification with the shared input schema and error contract. */
    private static SyncToolSpecification tool(String name, String description, boolean withOut,
                                              Function<Map<String, Object>, ObjectNode> body) {
        Function<Map<String, Object>, ObjectNode> checked = arguments -> {
            ToolArguments.rejectUnknownArguments(arguments, withOut);
            return body.apply(arguments);
        };
        return SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name(name)
                        .description(description)
                        .inputSchema(inputSchema(withOut))
                        .build())
                .callHandler((exchange, request) -> call(name, request.arguments(), checked))
                .build();
    }

    /**
     * Runs one tool call and maps the outcome to a {@link McpSchema.CallToolResult}. Argument and
     * generation failures become {@code isError} results carrying the same JSON document shape, so
     * an agent always has one contract to parse — a raw exception would surface as an opaque
     * protocol error instead.
     */
    private static McpSchema.CallToolResult call(String name, Map<String, Object> arguments,
                                                 Function<Map<String, Object>, ObjectNode> body) {
        ObjectNode document;
        try {
            document = body.apply(arguments == null ? Map.of() : arguments);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            document = failure(name, message);
        }
        boolean ok = STATUS_OK.equals(document.path(KEY_STATUS).asText());
        return McpSchema.CallToolResult.builder()
                .addTextContent(document.toString())
                .isError(!ok)
                .build();
    }

    /** The JSON Schema shared by the three tools; {@value TOOL_GENERATE} adds {@code out}. */
    private static McpSchema.JsonSchema inputSchema(boolean withOut) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(ARG_SOURCES, Map.of(
                SCHEMA_TYPE, "array",
                SCHEMA_ITEMS, Map.of(SCHEMA_TYPE, "string"),
                SCHEMA_DESCRIPTION, "Source root directories, or individual .java files, to "
                        + "generate from. Directories are scanned recursively."));
        properties.put(ARG_CLASSPATH, Map.of(
                SCHEMA_TYPE, "string",
                SCHEMA_DESCRIPTION, "Compile classpath for the sources, as one "
                        + "path-separator-separated string ('" + File.pathSeparator + "' on this "
                        + "platform). Required (FR-005): it must carry the Spring AI and Spring "
                        + "Web types the generated wrappers reference, or generation will not "
                        + "compile."));
        properties.put(ARG_OPTIONS, Map.of(
                SCHEMA_TYPE, TYPE_OBJECT,
                SCHEMA_ADDITIONAL_PROPERTIES, Map.of(SCHEMA_TYPE, "string"),
                SCHEMA_DESCRIPTION, "Annotation-processor options as string-to-string pairs, "
                        + "e.g. {\"ai.atlas.api.major\": \"2\"}. Must match the target build's "
                        + "options for the output to match it."));
        List<String> required = new ArrayList<>(List.of(ARG_SOURCES, ARG_CLASSPATH));
        if (withOut) {
            properties.put(ARG_OUT, Map.of(
                    SCHEMA_TYPE, "string",
                    SCHEMA_DESCRIPTION, "Directory to write generated output into. Created if "
                            + "missing; a re-run replaces its previous contents atomically."));
            required.add(ARG_OUT);
        }
        return new McpSchema.JsonSchema(TYPE_OBJECT, properties, required, false, null, null);
    }

    /**
     * Renders a {@link GenerationResult} as the stable tool-result document. Every key is present
     * in every result — {@code outputDir} and {@code openApi} hold explicit JSON {@code null} when
     * inapplicable — so consumers never have to distinguish "missing" from "null".
     */
    private static ObjectNode document(String tool, GenerationResult result, String failure) {
        boolean ok = result.success() && failure == null;
        ObjectNode root = base(tool, ok ? STATUS_OK : STATUS_ERROR);
        root.put(KEY_SUMMARY, summary(tool, result, failure));
        if (result.outputDir() == null) {
            root.putNull(KEY_OUTPUT_DIR);
        } else {
            root.put(KEY_OUTPUT_DIR, result.outputDir().toString());
        }
        ArrayNode files = root.putArray(KEY_FILES);
        ObjectNode counts = root.putObject(KEY_COUNTS);
        for (GeneratedFile file : result.files()) {
            ObjectNode node = files.addObject();
            node.put(KEY_KIND, file.kind().name());
            node.put(KEY_RELATIVE_PATH, file.relativePath());
            if (file.path() == null) {
                node.putNull(KEY_PATH);
            } else {
                node.put(KEY_PATH, file.path().toString());
            }
            node.put(KEY_SOURCE, file.kind().isSource());
            String kind = file.kind().name();
            counts.put(kind, counts.path(kind).asInt() + 1);
        }
        openApi(root, result);
        ArrayNode diagnostics = root.putArray(KEY_DIAGNOSTICS);
        for (Diagnostic diagnostic : result.diagnostics()) {
            ObjectNode node = diagnostics.addObject();
            node.put(KEY_SEVERITY, diagnostic.severity().name());
            node.put(KEY_MESSAGE, diagnostic.message());
            node.put(KEY_SOURCE, diagnostic.source());
        }
        ArrayNode errors = root.putArray(KEY_ERRORS);
        if (!result.success()) {
            errors.add(GENERATION_FAILED);
        } else if (failure != null) {
            errors.add(failure);
        }
        ArrayNode services = root.putArray(KEY_SERVICES);
        result.discoveredServices().forEach(services::add);
        return root;
    }

    /**
     * Fills {@code openApi} with the document and, when it was written to disk, its path — or
     * with explicit JSON {@code null} when this run produced no document.
     */
    private static void openApi(ObjectNode root, GenerationResult result) {
        String openApi = result.openApi();
        if (openApi == null) {
            root.putNull(KEY_OPEN_API);
            return;
        }
        ObjectNode node = root.putObject(KEY_OPEN_API);
        Path written = result.files().stream()
                .filter(f -> f.kind() == GeneratedFile.Kind.OPENAPI && f.path() != null)
                .filter(f -> openApi.equals(f.content()))
                .map(GeneratedFile::path)
                .findFirst()
                .orElse(null);
        if (written == null) {
            node.putNull(KEY_PATH);
        } else {
            node.put(KEY_PATH, written.toString());
        }
        node.put(KEY_DOCUMENT, openApi);
    }

    /**
     * One human-readable line saying what the run did — the review headline (FR-007). A failed
     * compilation reports its error diagnostics; a semantic failure (clean compile, but the tool
     * could not deliver) is its own headline, not a bogus "failed with 0 error diagnostic(s)".
     */
    private static String summary(String tool, GenerationResult result, String failure) {
        if (!result.success()) {
            long errorCount = result.errors().size();
            return "Generation failed with " + errorCount + " error diagnostic(s); "
                    + "no output was written.";
        }
        if (failure != null) {
            return failure;
        }
        int services = result.discoveredServices().size();
        int files = result.files().size();
        if (TOOL_GENERATE.equals(tool)) {
            return "Generated " + files + " file(s) from " + services + " @AgenticExposed "
                    + "service(s) into " + result.outputDir() + ".";
        }
        if (TOOL_OPENAPI.equals(tool)) {
            return "OpenAPI document produced from " + services + " @AgenticExposed service(s); "
                    + "nothing was written to disk.";
        }
        return "Discovered " + services + " @AgenticExposed service(s); " + files
                + " artifact(s) would be generated. Nothing was written to disk.";
    }

    /**
     * A complete document for a call that failed before a {@link GenerationResult} existed. Same
     * keys, same order as {@link #document}: {@code outputDir} and {@code openApi} are explicit
     * JSON {@code null}, never missing.
     */
    private static ObjectNode failure(String tool, String message) {
        ObjectNode root = base(tool, STATUS_ERROR);
        root.put(KEY_SUMMARY, "Tool call failed: " + message);
        root.putNull(KEY_OUTPUT_DIR);
        root.putArray(KEY_FILES);
        root.putObject(KEY_COUNTS);
        root.putNull(KEY_OPEN_API);
        root.putArray(KEY_DIAGNOSTICS);
        root.putArray(KEY_ERRORS).add(message);
        root.putArray(KEY_SERVICES);
        return root;
    }

    /** Starts a document with the keys every result opens with, in fixed order. */
    private static ObjectNode base(String tool, String status) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put(KEY_TOOL, tool);
        root.put(KEY_STATUS, status);
        return root;
    }

}
