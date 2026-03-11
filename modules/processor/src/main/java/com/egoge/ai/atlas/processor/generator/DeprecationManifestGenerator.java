/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.processor.model.ServiceModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;
import com.egoge.ai.atlas.processor.util.VersionSelector;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates {@code META-INF/ai-atlas/deprecation-manifest.json} containing
 * deprecation metadata for all active API-channel REST endpoints. The runtime
 * {@code DeprecationHeaderFilter} reads this manifest at startup — no runtime
 * annotation scanning needed.
 */
public final class DeprecationManifestGenerator {

    private static final String RESOURCE_PATH = "META-INF/ai-atlas/deprecation-manifest.json";

    private DeprecationManifestGenerator() {}

    /**
     * Generates the deprecation manifest from the service registry.
     *
     * @param services    all registered service models
     * @param apiBasePath configured base path (e.g. {@code /api})
     * @param apiMajor    configured major version
     * @param filer       JSR 269 filer for writing resources
     * @param messager    JSR 269 messager for diagnostics
     */
    public static void generate(List<ServiceModel> services, String apiBasePath,
                                int apiMajor, Filer filer, Messager messager) {
        var entries = new ArrayList<String>();
        for (ServiceModel service : services) {
            String servicePath = apiBasePath + "/v" + apiMajor + "/"
                    + RestControllerGenerator.toKebabCase(service.serviceClassName().simpleName());
            for (MethodModel method : service.methods()) {
                if (!method.channels().contains("API")
                        || !VersionSelector.isActive(method, apiMajor)) {
                    continue;
                }
                String path = servicePath + "/" + RestControllerGenerator.toKebabCase(method.methodName());
                String httpMethod = method.parameters().isEmpty() ? "GET" : "POST";
                boolean deprecated = VersionSelector.isDeprecated(method, apiMajor);
                int deprecatedSince = deprecated ? method.apiDeprecatedSince() : 0;
                String replacement = deprecated ? method.apiReplacement() : "";
                entries.add(formatEntry(httpMethod, path, deprecated, deprecatedSince, replacement));
            }
        }
        writeManifest(entries, apiMajor, apiBasePath, filer, messager);
    }

    private static String formatEntry(String httpMethod, String path,
                                      boolean deprecated, int deprecatedSince,
                                      String replacement) {
        return "    {"
                + "\"method\":\"" + httpMethod + "\","
                + "\"path\":\"" + path + "\","
                + "\"deprecated\":" + deprecated + ","
                + "\"deprecatedSince\":" + deprecatedSince + ","
                + "\"replacement\":\"" + escapeJson(replacement) + "\""
                + "}";
    }

    private static void writeManifest(List<String> entries, int apiMajor,
                                      String apiBasePath, Filer filer, Messager messager) {
        try {
            var resource = filer.createResource(StandardLocation.CLASS_OUTPUT, "", RESOURCE_PATH);
            try (Writer writer = resource.openWriter()) {
                writer.write("{\n");
                writer.write("  \"apiMajor\":" + apiMajor + ",\n");
                writer.write("  \"basePath\":\"" + escapeJson(apiBasePath) + "\",\n");
                writer.write("  \"endpoints\":[\n");
                for (int i = 0; i < entries.size(); i++) {
                    writer.write(entries.get(i));
                    if (i < entries.size() - 1) {
                        writer.write(",");
                    }
                    writer.write("\n");
                }
                writer.write("  ]\n");
                writer.write("}\n");
            }
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "[ai-atlas] Generated: " + RESOURCE_PATH);
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] Failed to write " + RESOURCE_PATH + ": " + e.getMessage());
        }
    }

    /** Full JSON string escaping including control characters. */
    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        var sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
