/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.generator.ApiVersionPropertiesGenerator;
import com.egoge.ai.atlas.processor.generator.DeprecationManifestGenerator;
import com.egoge.ai.atlas.processor.generator.McpToolsResourceGenerator;
import com.egoge.ai.atlas.processor.generator.OpenApiGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static com.egoge.ai.atlas.processor.AgenticProcessor.OPT_API_BASE_PATH;
import static com.egoge.ai.atlas.processor.AgenticProcessor.OPT_API_MAJOR;
import static com.egoge.ai.atlas.processor.AgenticProcessor.OPT_COLLECTIONS;
import static com.egoge.ai.atlas.processor.AgenticProcessor.OPT_CONSTRAINTS;
import static com.egoge.ai.atlas.processor.AgenticProcessor.OPT_OPENAPI_INFO_VERSION;
import static com.egoge.ai.atlas.processor.AgenticProcessor.OPT_PROJECTIONS;

/**
 * The reserved {@code META-INF} paths a compilation's contract may produce, "the paths in one
 * place" (F2): the fixed set written by the IR, the contract gate and the generators, plus the
 * versioned OpenAPI documents, and the {@link Manifest} that records their digests.
 */
public final class ContractResources {

    /** Where a compilation's {@link Manifest} is written. */
    public static final String MANIFEST_PATH = "META-INF/ai-atlas/contract-resources.json";

    private static final String OPENAPI_ALIAS_PATH = OpenApiGenerator.RESOURCE_DIR + "openapi.json";

    /** Matches {@code META-INF/openapi/openapi-v<N>.json}, where {@code N} has no leading zero. */
    private static final Pattern VERSIONED_OPENAPI = Pattern.compile(
            Pattern.quote(OpenApiGenerator.RESOURCE_DIR) + "openapi-v[1-9][0-9]*\\.json");

    private static final Set<String> FIXED_RESERVED = Set.of(
            ContractIr.RESOURCE_PATH,
            ContractGate.DIFF_RESOURCE_PATH,
            McpToolsResourceGenerator.RESOURCE_PATH,
            ApiVersionPropertiesGenerator.RESOURCE_PATH,
            DeprecationManifestGenerator.RESOURCE_PATH,
            MANIFEST_PATH,
            OPENAPI_ALIAS_PATH);

    private ContractResources() {
    }

    /**
     * True iff {@code classOutputRelativePath} is one of the fixed reserved paths, or a versioned
     * OpenAPI document ({@code openapi-v1.json}, {@code openapi-v12.json}, …). Any other path,
     * including another file under {@code META-INF/ai-atlas/}, is not reserved (OQ-5).
     *
     * @param classOutputRelativePath the path, relative to the class output root
     * @return whether the path is reserved
     */
    public static boolean isReserved(String classOutputRelativePath) {
        return FIXED_RESERVED.contains(classOutputRelativePath)
                || VERSIONED_OPENAPI.matcher(classOutputRelativePath).matches();
    }

    /**
     * The subset of {@code m.artifacts().keySet()} a release snapshot keeps: the IR, the versioned
     * OpenAPI document (not the {@code openapi.json} alias) and {@code mcp-tools.json}, when
     * present (D2.10).
     *
     * @param m the manifest
     * @return the snapshotted artifact paths, in no particular order
     */
    public static Set<String> snapshotted(Manifest m) {
        Set<String> result = new LinkedHashSet<>();
        for (String path : m.artifacts().keySet()) {
            if (path.equals(ContractIr.RESOURCE_PATH) || path.equals(McpToolsResourceGenerator.RESOURCE_PATH)
                    || VERSIONED_OPENAPI.matcher(path).matches()) {
                result.add(path);
            }
        }
        return result;
    }

    /**
     * The artifact paths a manifest for {@code m.configuration()} must list.
     *
     * @param m          the manifest
     * @param nonEmptyIr whether the compilation's IR has at least one entity or operation
     * @return the required artifact paths
     */
    public static Set<String> required(Manifest m, boolean nonEmptyIr) {
        Set<String> result = new LinkedHashSet<>();
        result.add(ContractIr.RESOURCE_PATH);
        if (nonEmptyIr) {
            result.add(OpenApiGenerator.RESOURCE_DIR + "openapi-v" + m.configuration().apiMajor() + ".json");
        }
        if (m.configuration().constraints()) {
            result.add(McpToolsResourceGenerator.RESOURCE_PATH);
        }
        return result;
    }

    /**
     * A compilation's contract-resources manifest: whether the compilation declared a contract or
     * ran with no annotations at all, its effective configuration, and the SHA-256 of every
     * reserved artifact it wrote, keyed by class-output-relative path.
     *
     * @param contract      {@code "declared"} or {@code "empty"}
     * @param configuration the compilation's effective {@code ai.atlas.*} configuration
     * @param artifacts     each reserved artifact's path, mapped to its lowercase hex SHA-256
     */
    public record Manifest(String contract, EffectiveOptions configuration, SortedMap<String, String> artifacts) {

        /** The {@code manifestVersion} this ai-atlas writes and the highest it reads. */
        public static final int MANIFEST_VERSION = 1;

        private static final String CONTRACT_DECLARED = "declared";
        private static final String CONTRACT_EMPTY = "empty";

        private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
        private static final ObjectMapper READER = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

        private static final String K_MANIFEST_VERSION = "manifestVersion";
        private static final String K_CONTRACT = "contract";
        private static final String K_CONFIGURATION = "configuration";
        private static final String K_ARTIFACTS = "artifacts";

        public Manifest {
            artifacts = Collections.unmodifiableSortedMap(new TreeMap<>(artifacts));
        }

        /** The manifest's canonical JSON text, through {@link IrJson#writeCanonical}. */
        public String write() {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put(K_MANIFEST_VERSION, MANIFEST_VERSION);
            doc.put(K_CONTRACT, contract);
            Map<String, Object> config = new LinkedHashMap<>();
            config.put(OPT_API_BASE_PATH, configuration.apiBasePath());
            config.put(OPT_API_MAJOR, configuration.apiMajor());
            config.put(OPT_COLLECTIONS, configuration.collections());
            config.put(OPT_CONSTRAINTS, configuration.constraints());
            config.put(OPT_OPENAPI_INFO_VERSION, configuration.openApiInfoVersion());
            config.put(OPT_PROJECTIONS, configuration.projections());
            doc.put(K_CONFIGURATION, config);
            doc.put(K_ARTIFACTS, new LinkedHashMap<String, Object>(artifacts));
            return IrJson.writeCanonical(doc);
        }

        /**
         * Reads a manifest, strictly.
         *
         * @param json the manifest's text
         * @return the manifest
         * @throws IllegalArgumentException if the text is not a manifest this ai-atlas reads, naming why
         */
        public static Manifest read(String json) {
            JsonNode root;
            try {
                root = READER.readTree(json);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("it is not valid JSON: " + e.getOriginalMessage());
            }
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("it is not a JSON object");
            }
            int manifestVersion = integer(root, K_MANIFEST_VERSION);
            if (manifestVersion > MANIFEST_VERSION) {
                throw new IllegalArgumentException("it has " + K_MANIFEST_VERSION + " " + manifestVersion
                        + ", but this ai-atlas reads " + K_MANIFEST_VERSION + " " + MANIFEST_VERSION
                        + " at most: it was written by a newer ai-atlas. Upgrade ai-atlas");
            }
            if (manifestVersion < 1) {
                throw new IllegalArgumentException("'" + K_MANIFEST_VERSION + "' must be at least 1");
            }
            String contract = string(root, K_CONTRACT);
            if (!CONTRACT_DECLARED.equals(contract) && !CONTRACT_EMPTY.equals(contract)) {
                throw new IllegalArgumentException("'" + K_CONTRACT + "' must be '" + CONTRACT_DECLARED + "' or '"
                        + CONTRACT_EMPTY + "', got " + contract);
            }
            EffectiveOptions configuration = readConfiguration(object(root, K_CONFIGURATION));
            SortedMap<String, String> artifacts = readArtifacts(object(root, K_ARTIFACTS));
            return new Manifest(contract, configuration, artifacts);
        }

        private static EffectiveOptions readConfiguration(JsonNode node) {
            return new EffectiveOptions(string(node, OPT_API_BASE_PATH), integer(node, OPT_API_MAJOR),
                    string(node, OPT_OPENAPI_INFO_VERSION), bool(node, OPT_CONSTRAINTS), bool(node, OPT_PROJECTIONS),
                    bool(node, OPT_COLLECTIONS));
        }

        private static SortedMap<String, String> readArtifacts(JsonNode node) {
            SortedMap<String, String> artifacts = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                String digest = entry.getValue().isTextual() ? entry.getValue().asText() : "";
                if (!SHA256.matcher(digest).matches() || entry.getKey().contains("..")) {
                    throw new IllegalArgumentException("'" + K_ARTIFACTS + "' must map paths to lowercase SHA-256"
                            + " digests, got " + entry.getKey() + ": " + entry.getValue());
                }
                artifacts.put(entry.getKey(), digest);
            }
            return artifacts;
        }

        private static int integer(JsonNode node, String key) {
            JsonNode value = node.get(key);
            if (value == null || !value.isInt()) {
                throw new IllegalArgumentException("'" + key + "' must be an integer");
            }
            return value.asInt();
        }

        private static String string(JsonNode node, String key) {
            JsonNode value = node.get(key);
            if (value == null || !value.isTextual()) {
                throw new IllegalArgumentException("'" + key + "' must be a string");
            }
            return value.asText();
        }

        private static boolean bool(JsonNode node, String key) {
            JsonNode value = node.get(key);
            if (value == null || !value.isBoolean()) {
                throw new IllegalArgumentException("'" + key + "' must be a boolean");
            }
            return value.asBoolean();
        }

        private static JsonNode object(JsonNode node, String key) {
            JsonNode value = node.get(key);
            if (value == null || !value.isObject()) {
                throw new IllegalArgumentException("'" + key + "' must be an object");
            }
            return value;
        }
    }
}
