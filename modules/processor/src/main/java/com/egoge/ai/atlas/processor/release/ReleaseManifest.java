/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.release;

import com.egoge.ai.atlas.processor.contract.IrJson;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * {@code release.json}, a release's manifest: the version, the contract's {@code apiMajor} and
 * {@code irVersion}, the previous release, the policy it was released under, and the SHA-256 of
 * every other file of the release. It is written in the IR's canonical JSON form, with no
 * timestamp, host or path, so the same release gives the same bytes. Its own format is versioned
 * by {@code manifestVersion}, independent of the IR's {@code irVersion}.
 *
 * @param version           the released version
 * @param apiMajor          the contract's {@code apiMajor}
 * @param irVersion         the {@code irVersion} of the released {@code api.ir.json}, as written
 * @param previous          the previous release, or {@code null} for the first one
 * @param tagName           the resolved git tag name this release is proved by, such as
 *                          {@code "v1.4.0"} (F1). Later proofs use this recorded name, not
 *                          whatever {@code agentic { release { tagName } } } currently is.
 * @param policy            the policy the release was checked against
 * @param digests           the lowercase hexadecimal SHA-256 of each other file, by file name, sorted
 * @param contractResources the released class output's contract-resources manifest, embedded verbatim
 */
public record ReleaseManifest(String version, int apiMajor, int irVersion, String previous, String tagName,
                              ReleasePolicy.Policy policy, Map<String, String> digests,
                              Map<String, Object> contractResources) {

    /** The {@code manifestVersion} this ai-atlas writes and the highest it reads. */
    public static final int MANIFEST_VERSION = 1;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final ObjectMapper READER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final String K_MANIFEST_VERSION = "manifestVersion";
    private static final String K_VERSION = "version";
    private static final String K_API_MAJOR = "apiMajor";
    private static final String K_IR_VERSION = "irVersion";
    private static final String K_PREVIOUS = "previous";
    private static final String K_TAG_NAME = "tagName";
    private static final String K_POLICY = "policy";
    private static final String K_MIN_DEPRECATED_RELEASES = "minDeprecatedReleases";
    private static final String K_MIN_API_MAJOR_ADVANCE = "minApiMajorAdvance";
    private static final String K_FAIL_ON_BREAKING = "failOnBreaking";
    private static final String K_SHA256 = "sha256";
    private static final String K_CONTRACT_RESOURCES = "contractResources";

    public ReleaseManifest {
        digests = Collections.unmodifiableMap(new TreeMap<>(digests));
        contractResources = Collections.unmodifiableMap(new LinkedHashMap<>(contractResources));
    }

    /** The manifest's canonical JSON text. */
    public String write() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(K_MANIFEST_VERSION, MANIFEST_VERSION);
        doc.put(K_VERSION, version);
        doc.put(K_API_MAJOR, apiMajor);
        doc.put(K_IR_VERSION, irVersion);
        doc.put(K_PREVIOUS, previous);
        doc.put(K_TAG_NAME, tagName);
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put(K_MIN_DEPRECATED_RELEASES, policy.minDeprecatedReleases());
        rules.put(K_MIN_API_MAJOR_ADVANCE, policy.minApiMajorAdvance());
        rules.put(K_FAIL_ON_BREAKING, policy.failOnBreaking());
        doc.put(K_POLICY, rules);
        doc.put(K_SHA256, new LinkedHashMap<String, Object>(digests));
        doc.put(K_CONTRACT_RESOURCES, contractResources);
        return IrJson.writeCanonical(doc);
    }

    /**
     * Reads a manifest.
     *
     * @param json the manifest's text
     * @return the manifest
     * @throws IllegalArgumentException if the text is not a manifest this ai-atlas reads, naming why
     */
    public static ReleaseManifest read(String json) {
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
        JsonNode previous = root.get(K_PREVIOUS);
        if (previous == null || !previous.isNull() && !previous.isTextual()) {
            throw new IllegalArgumentException("'" + K_PREVIOUS + "' must be a string or null");
        }
        JsonNode rules = object(root, K_POLICY);
        String tagName = string(root, K_TAG_NAME);
        JsonNode failOnBreaking = rules.get(K_FAIL_ON_BREAKING);
        if (failOnBreaking == null || !failOnBreaking.isBoolean()) {
            throw new IllegalArgumentException("'" + K_POLICY + "." + K_FAIL_ON_BREAKING + "' must be a boolean");
        }
        Map<String, String> digests = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = object(root, K_SHA256).fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String digest = entry.getValue().isTextual() ? entry.getValue().asText() : "";
            if (!SHA256.matcher(digest).matches() || entry.getKey().contains("/") || entry.getKey().contains("\\")) {
                throw new IllegalArgumentException("'" + K_SHA256 + "' must map file names to lowercase SHA-256"
                        + " digests, got " + entry.getKey() + ": " + entry.getValue());
            }
            digests.put(entry.getKey(), digest);
        }
        return new ReleaseManifest(string(root, K_VERSION), integer(root, K_API_MAJOR), integer(root, K_IR_VERSION),
                previous.isNull() ? null : previous.asText(), tagName,
                new ReleasePolicy.Policy(integer(rules, K_MIN_DEPRECATED_RELEASES),
                        integer(rules, K_MIN_API_MAJOR_ADVANCE), failOnBreaking.asBoolean()), digests,
                toMap(object(root, K_CONTRACT_RESOURCES)));
    }

    /**
     * Parses a contract-resources manifest's raw JSON text into the object tree {@link #write}
     * embeds verbatim under {@value #K_CONTRACT_RESOURCES}.
     *
     * @param json the manifest's text, such as {@code ContractResources.Manifest.write()}'s output
     * @return the parsed object tree
     * @throws IllegalArgumentException if the text is not valid JSON
     */
    public static Map<String, Object> contractResourcesOf(String json) {
        try {
            return toMap(READER.readTree(json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e.getOriginalMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode node) {
        return READER.convertValue(node, Map.class);
    }

    /**
     * The {@code irVersion} an IR document declares, as written, before any in-memory migration.
     *
     * @param irJson the IR document's text, already read successfully by {@link IrJson#parse}
     * @return its {@code irVersion}
     */
    public static int irVersionOf(String irJson) {
        try {
            return integer(READER.readTree(irJson), K_IR_VERSION);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e.getOriginalMessage(), e);
        }
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

    private static JsonNode object(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("'" + key + "' must be an object");
        }
        return value;
    }
}
