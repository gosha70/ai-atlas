/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.processor.constraints.Endpoint;
import com.egoge.ai.atlas.processor.rest.BodyType;
import com.egoge.ai.atlas.processor.rest.RestOperation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;

import java.util.Set;

/** The OpenAPI schema of a REST operation's request body. */
final class BodySchemas {

  /** Body types JSON carries as a string; a {@code String} body itself is a compile error. */
  private static final Set<String> STRING_BODIES = Set.of("java.util.UUID", "java.lang.Character",
      "char");

  private BodySchemas() {
  }

  /**
   * The schema of a request body: a reference to the input record an entity body binds; else the
   * shape the mapping classified from the body's type.
   */
  static Schema<?> bodySchema(RestOperation rest) {
    return rest.inputRecord() != null
        ? new Schema<>().$ref("#/components/schemas/" + rest.inputRecord().simpleName())
        : bodyTypeSchema(rest.bodyType());
  }

  /**
   * A classified body shape: an array of its items' schema, unconstrained for a raw collection's
   * elements, an enum as a string with its constants, every integral type as an integer, any other
   * scalar as its JSON type, and anything else an object.
   */
  private static Schema<?> bodyTypeSchema(BodyType body) {
    if (body.items() != null) {
      return new ArraySchema().items(bodyTypeSchema(body.items()));
    }
    return body.javaType() == null ? new Schema<>() : bodyValueSchema(body);
  }

  @SuppressWarnings({"rawtypes", "unchecked"}) // swagger-models setEnum() requires raw Schema cast
  private static Schema<?> bodyValueSchema(BodyType body) {
    String type = body.javaType();
    if (body.enumValues() != null) {
      Schema<?> schema = new Schema<>().type("string");
      ((Schema) schema).setEnum(body.enumValues());
      return schema;
    }
    if (OpenApiGenerator.STRING.toString().equals(type) || STRING_BODIES.contains(type)) {
      return new Schema<>().type("string");
    }
    if (OpenApiGenerator.BIG_DECIMAL.equals(type)) {
      return new Schema<>().type("number");
    }
    Schema<?> scalar = OpenApiGenerator.mapJavaTypeToSchema(type);
    if (Endpoint.integral(type) && !"integer".equals(scalar.getType())) {
      // byte, short, their boxes and BigInteger, which the type mapping leaves as its string default
      return new Schema<>().type("integer");
    }
    return "string".equals(scalar.getType()) ? new Schema<>().type("object") : scalar;
  }
}
