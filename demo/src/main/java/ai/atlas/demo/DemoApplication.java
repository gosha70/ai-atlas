/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.atlas.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Demo application showcasing the AI-ATLAS framework.
 *
 * <p>The annotation processor generates:
 * <ul>
 *   <li>PII-safe DTOs (e.g., {@code OrderDto})</li>
 *   <li>MCP tools (e.g., {@code OrderServiceMcpTool})</li>
 *   <li>REST controllers (e.g., {@code OrderServiceRestController})</li>
 *   <li>OpenAPI spec ({@code META-INF/openapi/openapi.json})</li>
 * </ul>
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
