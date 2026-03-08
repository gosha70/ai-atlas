/**
 * MCP (Model Context Protocol) server configuration for AI-ATLAS.
 *
 * <p>{@link ai.atlas.runtime.mcp.AgenticMcpConfiguration} auto-discovers
 * {@code @Service} beans with {@code @Tool} methods and registers them
 * as MCP tools via Spring AI's {@code ToolCallbackProvider}.
 *
 * <p>Enabled by default; disable with {@code ai.atlas.mcp.enabled=false}.
 */
package com.egoge.ai.atlas.runtime.mcp;
