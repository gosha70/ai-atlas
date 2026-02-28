/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.runtime.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the AI-ADAM runtime.
 */
@ConfigurationProperties(prefix = "ai.adam")
public class AgenticProperties {

    private final Mcp mcp = new Mcp();
    private final Audit audit = new Audit();
    private final Json json = new Json();

    public Mcp getMcp() {
        return mcp;
    }

    public Audit getAudit() {
        return audit;
    }

    public Json getJson() {
        return json;
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
}
