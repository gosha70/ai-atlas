/**
 * AI-ATLAS annotation definitions for controlling code generation.
 *
 * <p>This package contains the three core annotations that drive the AI-ATLAS
 * annotation processor:
 *
 * <ul>
 *   <li>{@link com.egoge.ai.atlas.annotations.AgenticEntity} — marks an entity class
 *       for DTO generation. Only fields annotated with {@code @AgenticField}
 *       are included in the generated DTO, ensuring PII-safe output by default.</li>
 *   <li>{@link com.egoge.ai.atlas.annotations.AgenticField} — marks individual fields for
 *       inclusion in the generated DTO, with attributes for description, display
 *       name, sensitivity, and allowed values.</li>
 *   <li>{@link com.egoge.ai.atlas.annotations.AgenticExposed} — marks a service class or
 *       method for MCP tool, REST controller, and OpenAPI spec generation.</li>
 * </ul>
 *
 * <p>This module has zero external dependencies.
 */
package com.egoge.ai.atlas.annotations;
