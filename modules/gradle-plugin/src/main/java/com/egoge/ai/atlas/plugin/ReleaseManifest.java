/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.IrJson;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SPIKE: {@code release.json}, the manifest of a released version, in the IR's canonical JSON
 * form: no timestamp, host or path, so the same release inputs give the same bytes.
 */
final class ReleaseManifest {

    private static final Pattern DIGEST = Pattern.compile("\"([^\"]+)\": \"([0-9a-f]{64})\"");

    private ReleaseManifest() {
    }

    static String write(String version, ContractIr ir, String previous, int minDeprecatedReleases,
                        int minDeprecatedMajors, Map<String, String> digests) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", version);
        doc.put("apiMajor", ir.apiMajor());
        doc.put("irVersion", ir.irVersion());
        doc.put("previous", previous);
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("minDeprecatedReleases", minDeprecatedReleases);
        policy.put("minDeprecatedMajors", minDeprecatedMajors);
        doc.put("deprecationPolicy", policy);
        doc.put("sha256", new LinkedHashMap<String, Object>(digests));
        return IrJson.writeCanonical(doc);
    }

    /** The file digests a manifest records; its only 64-hex-digit string values. */
    static Map<String, String> digests(String manifest) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher m = DIGEST.matcher(manifest);
        while (m.find()) {
            result.put(m.group(1), m.group(2));
        }
        return result;
    }
}
