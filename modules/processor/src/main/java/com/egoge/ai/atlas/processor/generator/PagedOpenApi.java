/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.WildcardTypeName;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The OpenAPI description of an operation with a paging contract, for {@link OpenApiGenerator}:
 * the query parameters a {@code Pageable} is bound from, and the response with its envelope or
 * declared bound.
 */
final class PagedOpenApi {

    private static final String MAP = "java.util.Map";
    private static final String OPTIONAL = "java.util.Optional";

    private PagedOpenApi() {
    }

    /**
     * What Spring Data's {@code PageableHandlerMethodArgumentResolver} reads for the {@code Pageable}:
     * {@code page} and {@code size}, and {@code sort} only when properties are allow-listed, as the
     * controller drops it otherwise. None is required, and no default is published: the defaults are
     * application configuration. The controller rejects, with {@code 400}, a page or size out of the
     * published range, so {@code minimum} and {@code maximum} hold.
     */
    static List<Parameter> pageableParameters(PagingContract paging) {
        Schema<?> size = new Schema<>().type("integer").format("int32").minimum(BigDecimal.ONE);
        if (paging.pageSizeCeiling() != null) {
            size.maximum(BigDecimal.valueOf(paging.pageSizeCeiling()));
        }
        List<Parameter> parameters = new ArrayList<>(List.of(
                new Parameter().in("query").name(PagingContract.PAGE_PARAM).required(false)
                        .description(PagingContract.PAGE_DESCRIPTION)
                        .schema(new Schema<>().type("integer").format("int32").minimum(BigDecimal.ZERO)),
                new Parameter().in("query").name(PagingContract.SIZE_PARAM).required(false)
                        .description("Page size: the most results to return, at least 1"
                                + (paging.pageSizeCeiling() != null ? " and at most " + paging.pageSizeCeiling() : "")
                                + "; when omitted, the application's default page size"
                                + " (spring.data.web.pageable.default-page-size). A size out of range is rejected,"
                                + " never clamped")
                        .schema(size)));
        if (!paging.sortable().isEmpty()) {
            parameters.add(new Parameter().in("query").name(PagingContract.SORT_PARAM).required(false)
                    .description("Sort order, property[,asc|desc], repeatable; sortable properties: "
                            + String.join(", ", paging.sortable()))
                    .schema(new ArraySchema().items(new Schema<>().type("string"))));
        }
        return parameters;
    }

    /**
     * The response of an operation with a paging contract: the {@code Page} or {@code Slice}
     * envelope the controller returns, or its plain response; a declared bound is {@code maxItems}
     * on the elements, or {@code maxProperties} on a map.
     */
    @SuppressWarnings({"rawtypes", "unchecked"}) // swagger-models properties() accepts raw Map<String, Schema>
    static Content responseContent(MethodModel method, PagingContract paging) {
        Integer bound = paging.resultBound();
        if (!paging.enveloped()) {
            Content optional = bound != null ? optionalCollectionContent(method.returnType(), bound) : null;
            if (optional != null) {
                return optional;
            }
            Content plain = OpenApiGenerator.buildResponseContent(method);
            Schema<?> schema = plain != null && plain.get(OpenApiGenerator.APPLICATION_JSON) != null
                    ? plain.get(OpenApiGenerator.APPLICATION_JSON).getSchema() : null;
            if (bound != null && schema instanceof ArraySchema) {
                schema.maxItems(bound);
            } else if (bound != null && schema != null && "object".equals(schema.getType())
                    && MAP.equals(rawName(method.returnType()))) {
                schema.maxProperties(bound);
            }
            return plain;
        }
        Schema<?> items = method.returnDtoType() != null
                ? new Schema<>().$ref("#/components/schemas/" + method.returnDtoType().simpleName())
                : paging.elementType() != null ? OpenApiGenerator.mapJavaTypeToSchema(paging.elementType().toString())
                : new Schema<>().type("object");
        ArraySchema content = new ArraySchema().items(items);
        if (bound != null) {
            content.maxItems(bound);
        }
        Map<String, Schema<?>> properties = new LinkedHashMap<>();
        properties.put("content", content);
        properties.put("number", new Schema<>().type("integer").format("int32"));
        properties.put("size", new Schema<>().type("integer").format("int32"));
        properties.put("hasNext", new Schema<>().type("boolean"));
        if (paging.envelope() == PagingContract.Envelope.PAGE) {
            properties.put("totalElements", new Schema<>().type("integer").format("int64"));
            properties.put("totalPages", new Schema<>().type("integer").format("int32"));
        }
        Schema<?> envelope = new Schema<>().type("object").required(new ArrayList<>(properties.keySet()));
        envelope.properties((Map) properties);
        return OpenApiGenerator.jsonContent(envelope);
    }

    /**
     * The bounded response of an {@code Optional} of a collection, array or map, which is written as
     * its content: an array with {@code maxItems}, or an object with {@code maxProperties};
     * {@code null} for any other return.
     */
    private static Content optionalCollectionContent(TypeName returnType, int bound) {
        if (!(returnType instanceof ParameterizedTypeName optional) || !OPTIONAL.equals(rawName(optional))
                || optional.typeArguments().size() != 1) {
            return null;
        }
        TypeName content = optional.typeArguments().get(0);
        if (content instanceof WildcardTypeName wildcard && !wildcard.upperBounds().isEmpty()) {
            content = wildcard.upperBounds().get(0);
        }
        if (MAP.equals(rawName(content))) {
            return OpenApiGenerator.jsonContent(new Schema<>().type("object").maxProperties(bound));
        }
        TypeName element = content instanceof ArrayTypeName array ? array.componentType()
                : content instanceof ParameterizedTypeName parameterized && parameterized.typeArguments().size() == 1
                ? parameterized.typeArguments().get(0) : null;
        Schema<?> items = element != null && (OpenApiGenerator.isScalar(element) || element.equals(OpenApiGenerator.STRING))
                ? OpenApiGenerator.mapJavaTypeToSchema(element.toString()) : new Schema<>().type("object");
        return OpenApiGenerator.jsonContent(new ArraySchema().items(items).maxItems(bound));
    }

    private static String rawName(TypeName type) {
        TypeName raw = type instanceof ParameterizedTypeName parameterized ? parameterized.rawType() : type;
        return raw instanceof ClassName className ? className.canonicalName() : null;
    }
}
