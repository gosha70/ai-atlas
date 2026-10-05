/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.processor.constraints.Endpoint;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.ContractProjection;
import com.egoge.ai.atlas.processor.model.EntityModel;
import com.egoge.ai.atlas.processor.model.FieldModel;
import com.egoge.ai.atlas.processor.model.ServiceModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.ParameterModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.ReturnKind;
import com.egoge.ai.atlas.processor.rest.BodyType;
import com.egoge.ai.atlas.processor.rest.InputRecord;
import com.egoge.ai.atlas.processor.rest.RestOperation;
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
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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
  static final String APPLICATION_JSON = "application/json";
  private static final String TEXT_PLAIN = "text/plain";
  static final ClassName STRING = ClassName.get(String.class);
  private static final String BIG_DECIMAL = "java.math.BigDecimal";
  private static final String BIG_INTEGER = "java.math.BigInteger";
  /** Body types JSON carries as a string; a {@code String} body itself is a compile error. */
  private static final Set<String> STRING_BODIES = Set.of("java.util.UUID", "java.lang.Character",
      "char");
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
   * @param routes       each API operation's resolved mapping by {@link ContractProjection#operationKey},
   *                     the model the controllers read too
   * @param inputRecords the input records request bodies bind, described as component schemas
   * @param constraints  the constraint surfaces, or {@code null} when {@code ai.atlas.constraints} is off
   * @param paging       the paging contracts by operation identity, or {@code null} when
   *                     {@code ai.atlas.collections} is off
   */
  public static void generate(
      List<EntityModel> entities,
      List<ServiceModel> services,
      Map<String, String> operationIds,
      Function<String, RestOperation> routes,
      Collection<InputRecord> inputRecords,
      String apiBasePath, int apiMajor, String infoVersion,
      ConstraintSurfaces constraints, Map<String, PagingContract> paging,
      Filer filer, Messager messager) {
    OpenAPI openAPI = buildSpec(entities, services, operationIds, routes, inputRecords, apiBasePath, apiMajor,
        infoVersion, constraints, paging);

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
      Function<String, RestOperation> routes,
      Collection<InputRecord> inputRecords,
      String apiBasePath, int apiMajor, String infoVersion,
      ConstraintSurfaces constraints, Map<String, PagingContract> paging) {
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
      schemas.put(entity.dtoName(), buildEntitySchema(entity, apiMajor, constraints));
    }
    // The input records request bodies bind: exactly what a body may set
    for (InputRecord input : inputRecords) {
      schemas.put(input.name().simpleName(), buildInputSchema(input, apiMajor, constraints));
    }
    components.schemas((Map) schemas);
    openAPI.components(components);

    // Paths from service methods
    List<OperationEntry> entries = new ArrayList<>();
    for (ServiceModel service : services) {
      collectServiceOperations(entries, service, apiBasePath, apiMajor, routes);
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
      pathItem.operation(entry.httpMethod(), buildOperation(entry.method(), operationId, apiMajor,
          constraints != null ? constraints.operation(entry.operationKey()) : null, constraints, entry.rest(),
          paging != null ? paging.get(entry.operationKey()) : null));
    }
    openAPI.paths(paths);

    return openAPI;
  }

  @SuppressWarnings({"rawtypes", "unchecked"}) // swagger-models properties() accepts raw Map<String, Schema>
  private static Schema<?> buildEntitySchema(EntityModel entity, int apiMajor,
                                             ConstraintSurfaces constraints) {
    Schema<?> schema = new Schema<>().type("object");
    String description = entity.classDescription().isEmpty()
        ? entity.dtoName() + " — PII-safe projection of " + entity.sourceClassName().simpleName()
        : entity.classDescription();
    schema.description(description);
    Map<String, Schema<?>> properties = new LinkedHashMap<>();
    for (FieldModel field : entity.fields()) {
      Schema<?> fieldSchema = buildFieldSchema(field, apiMajor);
      if (constraints != null) {
        ConstraintSurfaces.applyOpenApi(fieldSchema, constraints.field(entity, field));
      }
      properties.put(field.name(), fieldSchema);
    }
    schema.properties((Map) properties);
    return schema;
  }

  /** The schema of an input record: its components, the required ones listed as such. */
  @SuppressWarnings({"rawtypes", "unchecked"}) // swagger-models properties() accepts raw Map<String, Schema>
  private static Schema<?> buildInputSchema(InputRecord input, int apiMajor, ConstraintSurfaces constraints) {
    Schema<?> schema = new Schema<>().type("object");
    schema.description("The fields of " + input.entity().sourceClassName().simpleName()
        + " a request body may set; any other property is ignored");
    Map<String, Schema<?>> properties = new LinkedHashMap<>();
    List<String> required = new ArrayList<>();
    for (InputRecord.InputField component : input.fields()) {
      FieldModel field = component.field();
      Schema<?> fieldSchema = buildFieldSchema(field, apiMajor);
      if (constraints != null) {
        ConstraintSurfaces.applyOpenApi(fieldSchema, constraints.field(input.entity(), field));
      }
      properties.put(field.name(), fieldSchema);
      if (component.required()) {
        required.add(field.name());
      }
    }
    schema.properties((Map) properties);
    if (!required.isEmpty()) {
      schema.required(required);
    }
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

  static Schema<?> mapJavaTypeToSchema(String javaType) {
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
                                String operationKey, MethodModel method, RestOperation rest) {
  }

  private static void collectServiceOperations(List<OperationEntry> entries, ServiceModel service,
                                               String apiBasePath, int apiMajor,
                                               Function<String, RestOperation> routes) {
    for (MethodModel method : service.methods()) {
      if (!method.channels().contains("API") || !VersionSelector.isActive(method, apiMajor)) {
        continue;
      }
      // The mapping the controller reads too
      String operationKey = ContractProjection.operationKey(service.serviceClassName(), method);
      RestOperation rest = routes.apply(operationKey);
      entries.add(new OperationEntry(apiBasePath + "/v" + apiMajor + rest.fullPath(),
          PathItem.HttpMethod.valueOf(rest.httpMethod()), operationKey, method, rest));
    }
  }

  /**
   * @param irOperation the IR operation whose parameters' requiredness and constraints the
   *                    parameters carry, or {@code null} when {@code ai.atlas.constraints} is off
   * @param constraints the constraint surfaces, or {@code null} when {@code ai.atlas.constraints} is off
   * @param paging      the method's paging contract, or {@code null} when it has none or
   *                    {@code ai.atlas.collections} is off
   */
  private static Operation buildOperation(MethodModel method, String operationId, int apiMajor,
                                          ContractIr.Operation irOperation, ConstraintSurfaces constraints,
                                          RestOperation rest, PagingContract paging) {
    Operation operation = new Operation();
    operation.operationId(operationId);
    operation.summary(method.description());
    if (VersionSelector.isDeprecated(method, apiMajor)) {
      operation.deprecated(true);
    }

    // Arguments are where the mapping locates them, as the controller binds them
    for (int i = 0; i < method.parameters().size(); i++) {
      ParameterModel param = method.parameters().get(i);
      if (paging != null && paging.replaces(i)) {
        PagedOpenApi.pageableParameters(paging).forEach(operation::addParametersItem);
        continue;
      }
      String in = rest.in(i);
      if (RestOperation.BODY.equals(in)) {
        boolean required = irOperation == null || ConstraintSurfaces.parameter(irOperation, i, param).required();
        operation.requestBody(new RequestBody().required(required)
            .content(jsonContent(bodySchema(rest))));
        continue;
      }
      boolean path = RestOperation.PATH.equals(in);
      Parameter parameter = new Parameter()
          .in(path ? "path" : "query")
          .name(param.name())
          .required(true)
          .schema(mapJavaTypeToSchema(param.typeName().toString()));
      if (!param.description().isEmpty()) {
        parameter.description(param.description());
      }
      if (irOperation != null) {
        ContractIr.Parameter irParam = ConstraintSurfaces.parameter(irOperation, i, param);
        // A path parameter is always required
        parameter.required(irParam.required() || path);
        if (!irParam.constraints().isEmpty()) {
          // The keywords apply only to the matching JSON type, which the flag-off mapping may not give
          parameter.schema(constrainedSchema(param.typeName(), constraints));
        }
        ConstraintSurfaces.applyOpenApi(parameter.getSchema(), irParam.constraints());
      } else if (paging != null && paging.optionalCursor(i)) {
        parameter.required(false);
      }
      operation.addParametersItem(parameter);
    }

    // Response, under the status the controller declares; a 204 or 205 carries no content
    int status = rest.status();
    ApiResponse success = new ApiResponse().description(status == 201 ? "Created"
        : RestOperation.NO_CONTENT_STATUSES.getOrDefault(status, "Success"));
    Content content = RestOperation.NO_CONTENT_STATUSES.containsKey(status) ? null
        : paging != null ? PagedOpenApi.responseContent(method, paging) : buildResponseContent(method);
    if (content != null) {
      success.content(content);
    }
    ApiResponses responses = new ApiResponses();
    responses.addApiResponse(String.valueOf(status), success);
    operation.responses(responses);

    return operation;
  }

  /**
   * The schema of a constrained parameter, typed as the constraint model types it (FR-014): an
   * integral type is an {@code integer}, {@code float}, {@code double} and {@code BigDecimal} are a
   * {@code number}, and an array or any {@code java.util.Collection} is an {@code array} with
   * {@code items}; anything else keeps its flag-off mapping.
   */
  private static Schema<?> constrainedSchema(TypeName type, ConstraintSurfaces constraints) {
    if (constraints.collection(type)) {
      TypeName element = ConstraintSurfaces.elementType(type);
      return new ArraySchema().items(element != null ? constrainedSchema(element, constraints) : new Schema<>());
    }
    String name = type.toString();
    Schema<?> schema = mapJavaTypeToSchema(name);
    if (Endpoint.integral(name) && !"integer".equals(schema.getType())) {
      return new Schema<>().type("integer");
    }
    if (BIG_DECIMAL.equals(name)) {
      return new Schema<>().type("number");
    }
    return schema;
  }

  /**
   * The schema of a request body: a reference to the input record an entity body binds; else the
   * shape the mapping classified from the body's type, an array for an array or a collection, an
   * enum as a string with its constants, a scalar as its JSON type, anything else an object.
   */
  private static Schema<?> bodySchema(RestOperation rest) {
    if (rest.inputRecord() != null) {
      return new Schema<>().$ref("#/components/schemas/" + rest.inputRecord().simpleName());
    }
    BodyType body = rest.bodyType();
    Schema<?> single = body.javaType() == null ? new Schema<>() : bodyValueSchema(body);
    return body.array() ? new ArraySchema().items(single) : single;
  }

  @SuppressWarnings({"rawtypes", "unchecked"}) // swagger-models setEnum() requires raw Schema cast
  private static Schema<?> bodyValueSchema(BodyType body) {
    String type = body.javaType();
    if (body.enumValues() != null) {
      Schema<?> schema = new Schema<>().type("string");
      ((Schema) schema).setEnum(body.enumValues());
      return schema;
    }
    if (STRING.toString().equals(type) || STRING_BODIES.contains(type)) {
      return new Schema<>().type("string");
    }
    if (BIG_DECIMAL.equals(type) || BIG_INTEGER.equals(type)) {
      return new Schema<>().type("number");
    }
    Schema<?> scalar = mapJavaTypeToSchema(type);
    if (Endpoint.integral(type) && !"integer".equals(scalar.getType())) {
      // byte and short, and their boxes, which the type mapping leaves as its string default
      return new Schema<>().type("integer");
    }
    return "string".equals(scalar.getType()) ? new Schema<>().type("object") : scalar;
  }

  /** Response content matching what the generated controller returns; {@code null} for void. */
  static Content buildResponseContent(MethodModel method) {
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
    // A String element too: Spring serializes a collection of strings as a JSON array of strings
    if (elementType != null && (isScalar(elementType) || elementType.equals(STRING))) {
      return jsonContent(new ArraySchema().items(mapJavaTypeToSchema(elementType.toString())));
    }
    return jsonContent(new Schema<>().type("object"));
  }

  static Content jsonContent(Schema<?> schema) {
    return new Content().addMediaType(APPLICATION_JSON, new MediaType().schema(schema));
  }

  /** A boxed or primitive number or boolean with a dedicated schema mapping. */
  static boolean isScalar(TypeName type) {
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
}
