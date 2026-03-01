/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.processor.generator;

import ai.adam.processor.model.EntityModel;
import ai.adam.processor.model.FieldModel;
import ai.adam.processor.model.ServiceModel;
import ai.adam.processor.model.ServiceModel.MethodModel;
import ai.adam.processor.model.ServiceModel.ParameterModel;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
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
    private static final String RESOURCE_PATH = "META-INF/openapi/openapi.json";

    private OpenApiGenerator() {
    }

    /**
     * Generates the OpenAPI spec and writes it as a resource file.
     */
    public static void generate(List<EntityModel> entities, List<ServiceModel> services,
                                Filer filer, Messager messager) {
        OpenAPI openAPI = buildSpec(entities, services);

        try {
            String json = serializeToJson(openAPI);
            var resource = filer.createResource(StandardLocation.CLASS_OUTPUT, "", RESOURCE_PATH);
            try (Writer writer = resource.openWriter()) {
                writer.write(json);
            }
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "[ai-adam] Generated OpenAPI spec: " + RESOURCE_PATH);
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-adam] Failed to write OpenAPI spec: " + e.getMessage());
        }
    }

    /**
     * Builds the OpenAPI object model (visible for testing).
     */
    static OpenAPI buildSpec(List<EntityModel> entities, List<ServiceModel> services) {
        OpenAPI openAPI = new OpenAPI();
        openAPI.openapi(OPENAPI_VERSION);
        openAPI.info(new Info()
                .title("AI-ADAM Generated API")
                .version("1.0.0")
                .description("Auto-generated API from @AgenticExposed services"));

        // Schemas from entity DTOs
        Components components = new Components();
        Map<String, Schema> schemas = new LinkedHashMap<>();
        for (EntityModel entity : entities) {
            schemas.put(entity.dtoName(), buildEntitySchema(entity));
        }
        components.schemas(schemas);
        openAPI.components(components);

        // Paths from service methods
        Paths paths = new Paths();
        for (ServiceModel service : services) {
            addServicePaths(paths, service);
        }
        openAPI.paths(paths);

        return openAPI;
    }

    private static Schema buildEntitySchema(EntityModel entity) {
        Schema schema = new Schema();
        schema.type("object");
        String description = entity.classDescription().isEmpty()
                ? entity.dtoName() + " — PII-safe projection of " + entity.sourceClassName().simpleName()
                : entity.classDescription();
        schema.description(description);
        Map<String, Schema> properties = new LinkedHashMap<>();
        for (FieldModel field : entity.fields()) {
            properties.put(field.name(), buildFieldSchema(field));
        }
        schema.properties(properties);
        return schema;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema buildFieldSchema(FieldModel field) {
        Schema schema;
        if (field.enumType()) {
            schema = new Schema().type("string");
        } else {
            schema = mapJavaTypeToSchema(field.typeName().toString());
        }
        if (!field.enumValues().isEmpty()) {
            schema.setEnum(field.enumValues());
        }
        if (!field.description().isEmpty()) {
            schema.description(field.description());
        }
        return schema;
    }

    @SuppressWarnings("rawtypes")
    private static Schema mapJavaTypeToSchema(String javaType) {
        return switch (javaType) {
            case "java.lang.String", "String" -> new Schema().type("string");
            case "java.lang.Long", "long", "Long" -> new Schema().type("integer").format("int64");
            case "java.lang.Integer", "int", "Integer" -> new Schema().type("integer").format("int32");
            case "java.lang.Double", "double", "Double" -> new Schema().type("number").format("double");
            case "java.lang.Float", "float", "Float" -> new Schema().type("number").format("float");
            case "java.lang.Boolean", "boolean", "Boolean" -> new Schema().type("boolean");
            default -> new Schema().type("string");
        };
    }

    private static void addServicePaths(Paths paths, ServiceModel service) {
        String serviceName = service.serviceClassName().simpleName();
        String basePath = "/api/v1/" + toKebabCase(serviceName);

        for (MethodModel method : service.methods()) {
            String path = basePath + "/" + toKebabCase(method.methodName());
            PathItem pathItem = new PathItem();
            Operation operation = buildOperation(method);

            if (method.parameters().isEmpty()) {
                pathItem.get(operation);
            } else {
                pathItem.post(operation);
            }

            paths.addPathItem(path, pathItem);
        }
    }

    @SuppressWarnings("rawtypes")
    private static Operation buildOperation(MethodModel method) {
        Operation operation = new Operation();
        operation.operationId(method.methodName());
        operation.summary(method.description());

        // Request body for methods with parameters
        if (!method.parameters().isEmpty()) {
            Schema requestSchema = new Schema();
            requestSchema.type("object");
            Map<String, Schema> props = new LinkedHashMap<>();
            for (ParameterModel param : method.parameters()) {
                Schema paramSchema = mapJavaTypeToSchema(param.typeName().toString());
                if (!param.description().isEmpty()) {
                    paramSchema.description(param.description());
                }
                props.put(param.name(), paramSchema);
            }
            requestSchema.properties(props);

            RequestBody requestBody = new RequestBody()
                    .required(true)
                    .content(new Content().addMediaType("application/json",
                            new MediaType().schema(requestSchema)));
            operation.requestBody(requestBody);
        }

        // Response
        ApiResponse response200 = new ApiResponse().description("Success");
        if (method.returnDtoType() != null) {
            Schema responseSchema;
            String dtoRef = "#/components/schemas/" + method.returnDtoType().simpleName();
            if (method.collectionReturn()) {
                responseSchema = new ArraySchema().items(new Schema().$ref(dtoRef));
            } else {
                responseSchema = new Schema().$ref(dtoRef);
            }
            response200.content(new Content().addMediaType("application/json",
                    new MediaType().schema(responseSchema)));
        }
        ApiResponses responses = new ApiResponses();
        responses.addApiResponse("200", response200);
        operation.responses(responses);

        return operation;
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
