/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.processor.contract.ContractProjection;
import com.egoge.ai.atlas.processor.model.EntityModel;
import com.egoge.ai.atlas.processor.model.FieldModel;
import com.egoge.ai.atlas.processor.model.ServiceModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.ParameterModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.ReturnKind;
import com.egoge.ai.atlas.processor.util.VersionSelector;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates an OpenAPI 3.0.3 specification (JSON) from entity and service models.
 *
 * <p>The spec is written to {@code META-INF/openapi/openapi.json} in the
 * compiler's CLASS_OUTPUT location, making it available on the classpath at runtime.
 *
 * <p>Uses swagger-models (io.swagger.v3) for the OpenAPI object model
 * and Jackson for JSON serialization.
 */
public final class OpenApiGenerator {

  private static final String OPENAPI_VERSION = "3.0.3";
  private static final String APPLICATION_JSON = "application/json";
  private static final String TEXT_PLAIN = "text/plain";
  private static final ClassName STRING = ClassName.get(String.class);
  /** Class-output-relative directory the OpenAPI specs are written to. */
  public static final String RESOURCE_DIR = "META-INF/openapi/";
  /** Unversioned alias emitted alongside the versioned spec. */
  public static final String LEGACY_RESOURCE_NAME = "openapi.json";

  /**
   * Returns the class-output-relative path of the spec emitted for {@code apiMajor}.
   *
   * @param apiMajor configured API major version
   * @return e.g. {@code META-INF/openapi/openapi-v2.json}
   */
  public static String versionedResourcePath(int apiMajor) {
    return RESOURCE_DIR + "openapi-v" + apiMajor + ".json";
  }

  private OpenApiGenerator() {
  }

  /**
   * Generates the OpenAPI spec and writes it as a resource file.
   *
   * @param operationIds the operationId of every active API operation, keyed by
   *                     {@link ContractProjection#operationKey}, as the projection assigns them
   */
  public static void generate(
      List<EntityModel> entities,
      List<ServiceModel> services,
      Map<String, String> operationIds,
      String apiBasePath, int apiMajor, String infoVersion,
      Filer filer, Messager messager) {
    OpenAPI openAPI = buildSpec(entities, services, operationIds, apiBasePath, apiMajor, infoVersion);

    try {
      String json = serializeToJson(openAPI);

      // Write versioned spec
      String versionedPath = versionedResourcePath(apiMajor);
      var versionedResource = filer.createResource(StandardLocation.CLASS_OUTPUT, "", versionedPath);
      try (Writer writer = versionedResource.openWriter()) {
        writer.write(json);
      }
      messager.printMessage(Diagnostic.Kind.NOTE,
          "[ai-atlas] Generated OpenAPI spec: " + versionedPath);

      // Write legacy alias
      String legacyPath = RESOURCE_DIR + LEGACY_RESOURCE_NAME;
      var legacyResource = filer.createResource(StandardLocation.CLASS_OUTPUT, "", legacyPath);
      try (Writer writer = legacyResource.openWriter()) {
        writer.write(json);
      }
      messager.printMessage(Diagnostic.Kind.NOTE,
          "[ai-atlas] Generated OpenAPI spec alias: " + legacyPath);
    } catch (IOException e) {
      messager.printMessage(Diagnostic.Kind.ERROR,
          "[ai-atlas] Failed to write OpenAPI spec: " + e.getMessage());
    }
  }

  /**
   * Builds the OpenAPI object model (visible for testing).
   */
  @SuppressWarnings({"rawtypes", "unchecked"}) // swagger-models schemas() accepts raw Map<String, Schema>
  static OpenAPI buildSpec(
      List<EntityModel> entities,
      List<ServiceModel> services,
      Map<String, String> operationIds,
      String apiBasePath, int apiMajor, String infoVersion) {
    OpenAPI openAPI = new OpenAPI();
    openAPI.openapi(OPENAPI_VERSION);
    openAPI.info(new Info()
        .title("AI-ATLAS Generated API")
        .version(infoVersion)
        .description("Auto-generated API from @AgenticExposed services"));

    // Schemas from entity DTOs
    Components components = new Components();
    Map<String, Schema<?>> schemas = new LinkedHashMap<>();
    for (EntityModel entity : entities) {
      schemas.put(entity.dtoName(), buildEntitySchema(entity, apiMajor));
    }
    components.schemas((Map) schemas);
    openAPI.components(components);

    // Paths from service methods
    List<OperationEntry> entries = new ArrayList<>();
    for (ServiceModel service : services) {
      collectServiceOperations(entries, service, apiBasePath, apiMajor);
    }
    Paths paths = new Paths();
    for (OperationEntry entry : entries) {
      String operationId = operationIds.get(entry.operationKey());
      if (operationId == null) {
        throw new IllegalStateException("No operationId projected for " + entry.operationKey());
      }
      PathItem pathItem = paths.get(entry.path());
      if (pathItem == null) {
        pathItem = new PathItem();
        paths.addPathItem(entry.path(), pathItem);
      }
      pathItem.operation(entry.httpMethod(),
          buildOperation(entry.method(), operationId, apiMajor));
    }
    openAPI.paths(paths);

    return openAPI;
  }

  @SuppressWarnings({"rawtypes", "unchecked"}) // swagger-models properties() accepts raw Map<String, Schema>
  private static Schema<?> buildEntitySchema(EntityModel entity, int apiMajor) {
    Schema<?> schema = new Schema<>().type("object");
    String description = entity.classDescription().isEmpty()
        ? entity.dtoName() + " — PII-safe projection of " + entity.sourceClassName().simpleName()
        : entity.classDescription();
    schema.description(description);
    Map<String, Schema<?>> properties = new LinkedHashMap<>();
    for (FieldModel field : entity.fields()) {
      properties.put(field.name(), buildFieldSchema(field, apiMajor));
    }
    schema.properties((Map) properties);
    return schema;
  }

  @SuppressWarnings({"rawtypes", "unchecked"}) // swagger-models setEnum() requires raw Schema cast
  private static Schema<?> buildFieldSchema(FieldModel field, int apiMajor) {
    Schema<?> schema;
    if (field.enumType()) {
      schema = new Schema<>().type("string");
    } else {
      schema = mapJavaTypeToSchema(field.typeName().toString());
    }
    if (!field.enumValues().isEmpty()) {
      ((Schema) schema).setEnum(field.enumValues());
    }
    if (!field.description().isEmpty()) {
      schema.description(field.description());
    }
    if (VersionSelector.isFieldDeprecated(field, apiMajor)) {
      schema.deprecated(true);
      String desc = schema.getDescription();
      if (desc == null) {
        desc = "";
      }
      String prefix = field.deprecatedMessage().isEmpty()
          ? "[DEPRECATED since v" + field.deprecatedSinceVersion() + "]"
          : "[DEPRECATED since v" + field.deprecatedSinceVersion()
              + ": " + field.deprecatedMessage() + "]";
      schema.description(prefix + (desc.isEmpty() ? "" : " " + desc));
    }
    return schema;
  }

  private static Schema<?> mapJavaTypeToSchema(String javaType) {
    return switch (javaType) {
      case "java.lang.Long", "long", "Long" -> new Schema<>().type("integer").format("int64");
      case "java.lang.Integer", "int", "Integer" -> new Schema<>().type("integer").format("int32");
      case "java.lang.Double", "double", "Double" -> new Schema<>().type("number").format("double");
      case "java.lang.Float", "float", "Float" -> new Schema<>().type("number").format("float");
      case "java.lang.Boolean", "boolean", "Boolean" -> new Schema<>().type("boolean");
      default -> new Schema<>().type("string");
    };
  }

  /** One operation of the document, in the order the controllers declare their mappings. */
  private record OperationEntry(String path, PathItem.HttpMethod httpMethod,
                                String operationKey, MethodModel method) {
  }

  private static void collectServiceOperations(List<OperationEntry> entries, ServiceModel service,
                                               String apiBasePath, int apiMajor) {
    String serviceName = service.serviceClassName().simpleName();
    String basePath = apiBasePath + "/v" + apiMajor + "/" + toKebabCase(serviceName);

    for (MethodModel method : service.methods()) {
      if (!method.channels().contains("API") || !VersionSelector.isActive(method, apiMajor)) {
        continue;
      }
      String path = basePath + "/" + toKebabCase(method.methodName());
      PathItem.HttpMethod httpMethod = method.parameters().isEmpty()
          ? PathItem.HttpMethod.GET : PathItem.HttpMethod.POST;
      entries.add(new OperationEntry(path, httpMethod,
          ContractProjection.operationKey(service.serviceClassName(), method), method));
    }
  }

  private static Operation buildOperation(MethodModel method, String operationId, int apiMajor) {
    Operation operation = new Operation();
    operation.operationId(operationId);
    operation.summary(method.description());
    if (VersionSelector.isDeprecated(method, apiMajor)) {
      operation.deprecated(true);
    }

    // Arguments are query parameters, matching the controller's @RequestParam binding
    for (ParameterModel param : method.parameters()) {
      Parameter parameter = new Parameter()
          .in("query")
          .name(param.name())
          .required(true)
          .schema(mapJavaTypeToSchema(param.typeName().toString()));
      if (!param.description().isEmpty()) {
        parameter.description(param.description());
      }
      operation.addParametersItem(parameter);
    }

    // Response
    ApiResponse response200 = new ApiResponse().description("Success");
    Content content = buildResponseContent(method);
    if (content != null) {
      response200.content(content);
    }
    ApiResponses responses = new ApiResponses();
    responses.addApiResponse("200", response200);
    operation.responses(responses);

    return operation;
  }

  /** Response content matching what the generated controller returns; {@code null} for void. */
  private static Content buildResponseContent(MethodModel method) {
    if (method.returnDtoType() != null) {
      String dtoRef = "#/components/schemas/" + method.returnDtoType().simpleName();
      Schema<?> dtoSchema = new Schema<>().$ref(dtoRef);
      return jsonContent(method.returnKind() != ReturnKind.NONE
          ? new ArraySchema().items(dtoSchema) : dtoSchema);
    }
    TypeName returnType = method.returnType();
    if (returnType.equals(TypeName.VOID)) {
      return null;
    }
    if (returnType.equals(STRING)) {
      return new Content().addMediaType(TEXT_PLAIN,
          new MediaType().schema(new Schema<>().type("string")));
    }
    if (isScalar(returnType)) {
      return jsonContent(mapJavaTypeToSchema(returnType.toString()));
    }
    TypeName elementType = elementType(returnType, method.returnKind());
    if (elementType != null && isScalar(elementType)) {
      return jsonContent(new ArraySchema().items(mapJavaTypeToSchema(elementType.toString())));
    }
    return jsonContent(new Schema<>().type("object"));
  }

  private static Content jsonContent(Schema<?> schema) {
    return new Content().addMediaType(APPLICATION_JSON, new MediaType().schema(schema));
  }

  /** A boxed or primitive number or boolean with a dedicated schema mapping. */
  private static boolean isScalar(TypeName type) {
    return !"string".equals(mapJavaTypeToSchema(type.toString()).getType());
  }

  private static TypeName elementType(TypeName type, ReturnKind returnKind) {
    if (returnKind == ReturnKind.ARRAY && type instanceof ArrayTypeName array) {
      return array.componentType();
    }
    if (returnKind != ReturnKind.NONE && type instanceof ParameterizedTypeName parameterized
        && parameterized.typeArguments().size() == 1) {
      return parameterized.typeArguments().get(0);
    }
    return null;
  }

  static String serializeToJson(OpenAPI openAPI) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    mapper.enable(SerializationFeature.INDENT_OUTPUT);
    // Exclude internal swagger-models properties that aren't part of the OpenAPI spec
    mapper.addMixIn(Schema.class, SwaggerInternalMixin.class);
    mapper.addMixIn(MediaType.class, SwaggerInternalMixin.class);
    return mapper.writeValueAsString(openAPI);
  }

  @JsonIgnoreProperties({"exampleSetFlag", "types"})
  abstract static class SwaggerInternalMixin {
  }

  private static String toKebabCase(String camelCase) {
    return camelCase
        .replaceAll("([a-z])([A-Z])", "$1-$2")
        .toLowerCase();
  }
}
