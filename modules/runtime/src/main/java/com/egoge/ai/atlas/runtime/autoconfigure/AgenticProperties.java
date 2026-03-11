/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the AI-ATLAS runtime.
 */
@ConfigurationProperties(prefix = "ai.atlas")
public class AgenticProperties {

    private final Mcp mcp = new Mcp();
    private final Audit audit = new Audit();
    private final Json json = new Json();
    private final Api api = new Api();

    public Mcp getMcp() {
        return mcp;
    }

    public Audit getAudit() {
        return audit;
    }

    public Json getJson() {
        return json;
    }

    public Api getApi() {
        return api;
    }

    public static class Mcp {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Audit {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * Configuration for the Hibernate-safe JSON serialization module.
     */
    public static class Json {
        /**
         * When true, produces enriched JSON with field descriptions and
         * valid values for LLM/MCP consumption. When false (default),
         * produces flat JSON suitable for REST API responses.
         */
        private boolean enriched = false;

        /**
         * Whether to include field descriptions in enriched JSON output.
         */
        private boolean includeDescriptions = true;

        /**
         * Whether to include enum valid values in enriched JSON output.
         */
        private boolean includeValidValues = true;

        public boolean isEnriched() {
            return enriched;
        }

        public void setEnriched(boolean enriched) {
            this.enriched = enriched;
        }

        public boolean isIncludeDescriptions() {
            return includeDescriptions;
        }

        public void setIncludeDescriptions(boolean includeDescriptions) {
            this.includeDescriptions = includeDescriptions;
        }

        public boolean isIncludeValidValues() {
            return includeValidValues;
        }

        public void setIncludeValidValues(boolean includeValidValues) {
            this.includeValidValues = includeValidValues;
        }
    }

    /**
     * API version and path configuration.
     * Must match the values used during annotation processing.
     */
    public static class Api {
        private String basePath = "/api";
        private int major = 1;
        private boolean deprecationHeadersEnabled = true;
        private String deprecationDocUrl = "";
        private VersionNegotiation versionNegotiation = new VersionNegotiation();

        public String getBasePath() { return basePath; }
        public void setBasePath(String basePath) {
            this.basePath = normalizeBasePath(basePath);
        }
        public int getMajor() { return major; }
        public void setMajor(int major) {
            if (major < 1) {
                throw new IllegalArgumentException(
                        "ai.atlas.api.major must be a positive integer. Got: " + major);
            }
            this.major = major;
        }
        public boolean isDeprecationHeadersEnabled() { return deprecationHeadersEnabled; }
        public void setDeprecationHeadersEnabled(boolean enabled) {
            this.deprecationHeadersEnabled = enabled;
        }
        public String getDeprecationDocUrl() { return deprecationDocUrl; }
        public void setDeprecationDocUrl(String url) { this.deprecationDocUrl = url; }
        public VersionNegotiation getVersionNegotiation() { return versionNegotiation; }
        public void setVersionNegotiation(VersionNegotiation vn) { this.versionNegotiation = vn; }

        static String normalizeBasePath(String path) {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException(
                        "ai.atlas.api.base-path must not be blank");
            }
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException(
                        "ai.atlas.api.base-path must start with '/'. Got: " + path);
            }
            while (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }
            if ("/".equals(path)) {
                throw new IllegalArgumentException(
                        "ai.atlas.api.base-path must not be '/'. Use a path like '/api'.");
            }
            return path;
        }

        /**
         * Configuration for opt-in {@code Accept-Version} header validation.
         */
        public static class VersionNegotiation {
            private boolean enabled = false;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
        }
    }
}
