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

    public Mcp getMcp() {
        return mcp;
    }

    public Audit getAudit() {
        return audit;
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
}
