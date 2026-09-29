/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.TypeVariableName;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * How an operation's collection result is bounded, as {@link CollectionsOption} recognised it, and
 * the code the generated wrappers emit for it. Every index is a position in the method's
 * parameters, {@code -1} when absent. Generators receive the contracts only with
 * {@code ai.atlas.collections} on, and only for operations with something to generate.
 *
 * @param style          how each call is bounded
 * @param envelope       the wire shape around the elements, from a Spring Data {@code Page} or {@code Slice} return
 * @param pageable       the Spring Data {@code Pageable} parameter
 * @param limit          the {@code @AgenticParam(paging = LIMIT)} parameter
 * @param cursor         the {@code @AgenticParam(paging = CURSOR)} parameter
 * @param cursorOptional whether clients may omit the cursor, as the Contract IR records it
 * @param maxResults     the declared bound: the page-size ceiling of a {@link Style#PAGEABLE}
 *                       contract, the result bound of a {@link Style#DECLARED} one; {@code -1} when none
 * @param sortable       the properties a REST client may sort a {@code Pageable} by, empty when none
 * @param elementType    the {@code Page} or {@code Slice} element type, or {@code null} when raw or no envelope
 */
public record PagingContract(Style style, Envelope envelope, int pageable, int limit, int cursor,
                             boolean cursorOptional, int maxResults, List<String> sortable, TypeName elementType) {

    /** The MCP input and REST query parameter carrying the zero-based page number. */
    public static final String PAGE_PARAM = "page";
    /** The MCP input and REST query parameter carrying the page size. */
    public static final String SIZE_PARAM = "size";
    /** The REST query parameter carrying sort orders, as Spring Data's resolver reads it. */
    public static final String SORT_PARAM = "sort";
    /** The MCP and OpenAPI description of {@link #PAGE_PARAM}. */
    static final String PAGE_DESCRIPTION = "Zero-based page number; 0 when omitted";
    /** The MCP and OpenAPI description of {@link #SIZE_PARAM}. */
    static final String SIZE_DESCRIPTION = "Page size: the most results to return, at least 1";

    static final String DATA_PACKAGE = "org.springframework.data.domain";
    private static final ClassName GENERATED = ClassName.get("javax.annotation.processing", "Generated");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName SET = ClassName.get("java.util", "Set");
    private static final ClassName PAGE_REQUEST = ClassName.get(DATA_PACKAGE, "PageRequest");
    private static final ClassName PAGEABLE = ClassName.get(DATA_PACKAGE, "Pageable");
    private static final ClassName SORT_ORDER = ClassName.get(DATA_PACKAGE, "Sort", "Order");
    private static final ClassName BOUND = ClassName.get("com.egoge.ai.atlas.annotations", "AgenticBound");
    private static final ClassName RESPONSE_STATUS_EXCEPTION =
            ClassName.get("org.springframework.web.server", "ResponseStatusException");
    private static final ClassName HTTP_STATUS = ClassName.get("org.springframework.http", "HttpStatus");
    private static final String PAGE_RESULT = "PageResult";
    private static final String SLICE_RESULT = "SliceResult";
    private static final String PREFIX = "[ai-atlas] ";

    /** How each call of an operation is bounded, as the Contract IR records it. */
    public enum Style {
        /** A Spring Data {@code Pageable} parameter: each call returns one page. */
        PAGEABLE,
        /** A declared {@code LIMIT} parameter the service honours. */
        LIMIT,
        /** A declared {@code maxResults}. */
        DECLARED,
        /** Nothing bounds a call. */
        NONE
    }

    /** What the wrappers return around the elements. */
    public enum Envelope {
        /** The elements as they are: a list, or the service's own type. */
        NONE,
        /** {@code PageResult}: a Spring Data {@code Page}, with its totals. */
        PAGE,
        /** {@code SliceResult}: a Spring Data {@code Slice}, with whether a next slice exists. */
        SLICE
    }

    public PagingContract {
        sortable = List.copyOf(sortable);
    }

    /** Whether the wrapper's parameter at {@code index} is the {@code Pageable}. */
    public boolean replaces(int index) {
        return index == pageable;
    }

    /** Whether the parameter at {@code index} is a cursor clients may omit. */
    public boolean optionalCursor(int index) {
        return index == cursor && cursorOptional;
    }

    /** The largest page size a client may request, or {@code null} when none is declared. */
    public Integer pageSizeCeiling() {
        return style == Style.PAGEABLE && maxResults != -1 ? maxResults : null;
    }

    /** The declared bound on the result's elements, or {@code null} when none is declared. */
    public Integer resultBound() {
        return style == Style.DECLARED ? maxResults : null;
    }

    /** Whether the wrappers return an envelope rather than the elements. */
    public boolean enveloped() {
        return envelope != Envelope.NONE;
    }

    /**
     * The nested envelope records a wrapper class declares for its methods' contracts, empty when
     * none of them returns a {@code Page} or {@code Slice}.
     */
    static List<TypeSpec> envelopeRecords(Collection<PagingContract> contracts) {
        boolean page = contracts.stream().anyMatch(c -> c != null && c.envelope == Envelope.PAGE);
        boolean slice = contracts.stream().anyMatch(c -> c != null && c.envelope == Envelope.SLICE);
        TypeVariableName t = TypeVariableName.get("T");
        List<ParameterSpec> common = List.of(
                ParameterSpec.builder(ParameterizedTypeName.get(LIST, t), "content").build(),
                ParameterSpec.builder(int.class, "number").build(),
                ParameterSpec.builder(int.class, "size").build(),
                ParameterSpec.builder(boolean.class, "hasNext").build());
        List<TypeSpec> records = new ArrayList<>();
        if (page) {
            List<ParameterSpec> components = new ArrayList<>(common);
            components.add(ParameterSpec.builder(long.class, "totalElements").build());
            components.add(ParameterSpec.builder(int.class, "totalPages").build());
            records.add(record(PAGE_RESULT, "One page of results, with the totals the service computed.", t,
                    components));
        }
        if (slice) {
            records.add(record(SLICE_RESULT, "One slice of results; hasNext says whether another slice exists.", t,
                    common));
        }
        return records;
    }

    private static TypeSpec record(String name, String doc, TypeVariableName t, List<ParameterSpec> components) {
        return TypeSpec.recordBuilder(name)
                .addJavadoc(doc)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(GENERATED)
                        .addMember("value", "$S", "com.egoge.ai.atlas.processor").build())
                .addTypeVariable(t)
                .recordConstructor(MethodSpec.constructorBuilder().addParameters(components).build())
                .build();
    }

    /** The wrapper's return type for an envelope, {@code Enclosing.PageResult<Element>}. */
    TypeName envelopeType(ClassName enclosing, MethodModel method) {
        TypeName element = method.returnDtoType() != null ? method.returnDtoType()
                : elementType != null ? elementType : ClassName.OBJECT;
        return ParameterizedTypeName.get(enclosing.nestedClass(envelope == Envelope.PAGE ? PAGE_RESULT : SLICE_RESULT),
                element);
    }

    /** Calls the service and wraps its Page or Slice, mapping each element to its DTO when there is one. */
    void addEnvelopeStatements(MethodSpec.Builder builder, ClassName enclosing, MethodModel method, CodeBlock callArgs) {
        builder.addStatement("var result = service.$L($L)", method.methodName(), callArgs);
        CodeBlock content = method.returnDtoType() != null && method.returnEntityType() != null
                ? CodeBlock.of("result.getContent().stream().map(e -> $T.fromEntity(($T) e)).toList()",
                        method.returnDtoType(), method.returnEntityType())
                : CodeBlock.of("result.getContent()");
        ClassName type = enclosing.nestedClass(envelope == Envelope.PAGE ? PAGE_RESULT : SLICE_RESULT);
        if (envelope == Envelope.PAGE) {
            builder.addStatement("return new $T<>($L, result.getNumber(), result.getSize(), result.hasNext(),"
                    + " result.getTotalElements(), result.getTotalPages())", type, content);
        } else {
            builder.addStatement("return new $T<>($L, result.getNumber(), result.getSize(), result.hasNext())",
                    type, content);
        }
    }

    /** {@code @AgenticBound(maxResults = N)} for a declared bound the runtime checks results against. */
    AnnotationSpec boundAnnotation() {
        Integer bound = resultBound();
        return bound == null ? null : AnnotationSpec.builder(BOUND).addMember("maxResults", "$L", bound).build();
    }

    /**
     * The MCP tool's argument for the {@code Pageable}: a request built from its page and size
     * inputs. {@code PageRequest.of} rejects a negative page or a size below 1.
     */
    static CodeBlock pageRequestArgument() {
        return CodeBlock.of("$T.of($L == null ? 0 : $L, $L)", PAGE_REQUEST, PAGE_PARAM, PAGE_PARAM, SIZE_PARAM);
    }

    /** Rejects an MCP page size above the ceiling, before the service is called. */
    void addMcpCeilingCheck(MethodSpec.Builder builder, String methodName) {
        Integer ceiling = pageSizeCeiling();
        if (ceiling != null) {
            builder.beginControlFlow("if ($L > $L)", SIZE_PARAM, ceiling)
                    .addStatement("throw new $T($S + $L)", IllegalArgumentException.class, PREFIX + methodName
                            + " accepts a page size of at most " + ceiling + "; got ", SIZE_PARAM)
                    .endControlFlow();
        }
    }

    /**
     * Rejects a REST page size above the ceiling, and a sort on a property that is not allow-listed,
     * with {@code 400}; drops the sort entirely when none is allow-listed. Nothing is clamped.
     */
    void addRestPageableChecks(MethodSpec.Builder builder, String param, String methodName) {
        Integer ceiling = pageSizeCeiling();
        if (ceiling != null) {
            builder.beginControlFlow("if ($L.isUnpaged() || $L.getPageSize() > $L)", param, param, ceiling)
                    .addStatement("throw new $T($T.BAD_REQUEST, $S)", RESPONSE_STATUS_EXCEPTION, HTTP_STATUS,
                            PREFIX + methodName + " accepts a page size of at most " + ceiling)
                    .endControlFlow();
        }
        if (sortable.isEmpty()) {
            // A sort on a property clients cannot see would leak its values through the order
            builder.addStatement("$L = $L.isPaged() ? $T.of($L.getPageNumber(), $L.getPageSize()) : $T.unpaged()",
                    param, param, PAGE_REQUEST, param, param, PAGEABLE);
            return;
        }
        CodeBlock allowed = CodeBlock.join(sortable.stream().map(p -> CodeBlock.of("$S", p)).toList(), ", ");
        builder.beginControlFlow("for ($T order : $L.getSort())", SORT_ORDER, param)
                .beginControlFlow("if (!$T.of($L).contains(order.getProperty()))", SET, allowed)
                .addStatement("throw new $T($T.BAD_REQUEST, $S + order.getProperty() + $S)",
                        RESPONSE_STATUS_EXCEPTION, HTTP_STATUS, PREFIX + methodName + " cannot be sorted by '",
                        "'; sortable: " + sortable)
                .endControlFlow()
                .endControlFlow();
    }

    /** The sentence the MCP tool description gains, so a model knows how to page or what bound applies. */
    String toolGuidance(List<String> parameterNames) {
        return switch (style) {
            case PAGEABLE -> "Results are paged: pass size (at least 1"
                    + (pageSizeCeiling() != null ? ", at most " + pageSizeCeiling() : "")
                    + ") and optionally page (zero-based, default 0)"
                    + (envelope == Envelope.PAGE ? "; the result carries hasNext, totalElements and totalPages."
                    : envelope == Envelope.SLICE ? "; the result carries hasNext." : ".");
            case LIMIT -> "Returns at most '" + parameterNames.get(limit) + "' results"
                    + (cursor != -1 ? "; pass '" + parameterNames.get(cursor) + "' to continue after a previous call."
                    : ".");
            case DECLARED -> "Returns at most " + maxResults + " results.";
            case NONE -> "";
        };
    }
}
