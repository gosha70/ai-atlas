/**
 * JSR 269 annotation processor for the AI-ATLAS framework.
 *
 * <p>The {@link com.egoge.ai.atlas.processor.AgenticProcessor} scans classes annotated with
 * {@link com.egoge.ai.atlas.annotations.AgenticEntity} and
 * {@link com.egoge.ai.atlas.annotations.AgenticExposed}, then generates:
 *
 * <ul>
 *   <li>PII-safe DTO records (via {@code generator.DtoGenerator})</li>
 *   <li>MCP tool wrappers (via {@code generator.McpToolGenerator})</li>
 *   <li>REST controllers (via {@code generator.RestControllerGenerator})</li>
 *   <li>OpenAPI 3.0.3 specifications (via {@code generator.OpenApiGenerator})</li>
 * </ul>
 *
 * <p>This module runs at compile time only and must not be added as a runtime dependency.
 *
 * @see com.egoge.ai.atlas.processor.AgenticProcessor
 */
package com.egoge.ai.atlas.processor;
