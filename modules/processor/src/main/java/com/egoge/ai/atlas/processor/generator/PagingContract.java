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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private static final ClassName WEB_REQUEST =
            ClassName.get("org.springframework.web.context.request", "WebRequest");
    private static final String CHECK_PAGEABLE = "checkPageable";
    private static final String PAGING_INPUT = "pagingInput";
    private static final String BAD_REQUEST_METHOD = "badPagingInput";
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

    /**
     * A name for a variable the generator declares in a wrapper, {@code base} unless one of the
     * service method's parameters, which the wrapper declares too, already has it.
     */
    static String unusedName(MethodModel method, String base) {
        Set<String> taken = new HashSet<>();
        method.parameters().forEach(p -> taken.add(p.name()));
        String name = base;
        while (taken.contains(name)) {
            name = name + "_";
        }
        return name;
    }

    /** Calls the service and wraps its Page or Slice, mapping each element to its DTO when there is one. */
    void addEnvelopeStatements(MethodSpec.Builder builder, ClassName enclosing, MethodModel method, CodeBlock callArgs) {
        String result = unusedName(method, "result");
        String element = unusedName(method, "e");
        builder.addStatement("var $N = service.$L($L)", result, method.methodName(), callArgs);
        CodeBlock content = method.returnDtoType() != null && method.returnEntityType() != null
                ? CodeBlock.of("$N.getContent().stream().map($N -> $T.fromEntity(($T) $N)).toList()",
                        result, element, method.returnDtoType(), method.returnEntityType(), element)
                : CodeBlock.of("$N.getContent()", result);
        ClassName type = enclosing.nestedClass(envelope == Envelope.PAGE ? PAGE_RESULT : SLICE_RESULT);
        if (envelope == Envelope.PAGE) {
            builder.addStatement("return new $T<>($L, $N.getNumber(), $N.getSize(), $N.hasNext(),"
                    + " $N.getTotalElements(), $N.getTotalPages())", type, content, result, result, result, result,
                    result);
        } else {
            builder.addStatement("return new $T<>($L, $N.getNumber(), $N.getSize(), $N.hasNext())",
                    type, content, result, result, result);
        }
    }

    /** {@code @AgenticBound(maxResults = N)} for a declared bound the runtime checks results against. */
    AnnotationSpec boundAnnotation() {
        Integer bound = resultBound();
        return bound == null ? null : AnnotationSpec.builder(BOUND).addMember("maxResults", "$L", bound).build();
    }

    /**
     * The MCP tool's argument for the {@code Pageable}: a request built from its page and size
     * inputs, once {@link #addMcpPagingChecks} has checked them.
     */
    static CodeBlock pageRequestArgument() {
        return CodeBlock.of("$T.of($L == null ? 0 : $L.intValue(), $L.intValue())", PAGE_REQUEST, PAGE_PARAM,
                PAGE_PARAM, SIZE_PARAM);
    }

    /** The largest page size a client may request: the ceiling, else the largest {@code int}. */
    private int maxPageSize() {
        Integer ceiling = pageSizeCeiling();
        return ceiling != null ? ceiling : Integer.MAX_VALUE;
    }

    /**
     * Rejects a missing or out-of-range MCP page or size, naming the input and its range, before the
     * service is called. The inputs are {@code Long}s, so a size above the largest {@code int} is
     * rejected here too. Nothing is clamped.
     */
    void addMcpPagingChecks(MethodSpec.Builder builder, String methodName) {
        String where = PREFIX + methodName + ": ";
        String sizeRange = "an integer from 1 to " + maxPageSize();
        builder.beginControlFlow("if ($L == null)", SIZE_PARAM)
                .addStatement("throw new $T($S)", IllegalArgumentException.class,
                        where + SIZE_PARAM + " is required: " + sizeRange)
                .endControlFlow()
                .beginControlFlow("if ($L < 1 || $L > $L)", SIZE_PARAM, SIZE_PARAM, maxPageSize())
                .addStatement("throw new $T($S + $L)", IllegalArgumentException.class,
                        where + SIZE_PARAM + " must be " + sizeRange + "; got ", SIZE_PARAM)
                .endControlFlow()
                .beginControlFlow("if ($L != null && ($L < 0 || $L > $L))", PAGE_PARAM, PAGE_PARAM, PAGE_PARAM,
                        Integer.MAX_VALUE)
                .addStatement("throw new $T($S + $L)", IllegalArgumentException.class,
                        where + PAGE_PARAM + " must be an integer from 0 to " + Integer.MAX_VALUE + "; got ",
                        PAGE_PARAM)
                .endControlFlow();
    }

    /**
     * Rejects out-of-range REST paging inputs, and a sort on a property that is not allow-listed,
     * with {@code 400}; drops the sort entirely when none is allow-listed. Nothing is clamped.
     *
     * @param param   the {@code Pageable} parameter
     * @param request the {@code WebRequest} parameter, whose raw page and size are checked
     * @param method  the operation, whose parameter names the generated locals avoid
     */
    void addRestPageableChecks(MethodSpec.Builder builder, String param, String request, MethodModel method) {
        String methodName = method.methodName();
        builder.addStatement("$L($L, $L, $S, $L)", CHECK_PAGEABLE, param, request, methodName, maxPageSize());
        if (sortable.isEmpty()) {
            // A sort on a property clients cannot see would leak its values through the order
            builder.addStatement("$L = $L.isPaged() ? $T.of($L.getPageNumber(), $L.getPageSize()) : $T.unpaged()",
                    param, param, PAGE_REQUEST, param, param, PAGEABLE);
            return;
        }
        CodeBlock allowed = CodeBlock.join(sortable.stream().map(p -> CodeBlock.of("$S", p)).toList(), ", ");
        String order = unusedName(method, "order");
        builder.beginControlFlow("for ($T $N : $L.getSort())", SORT_ORDER, order, param)
                .beginControlFlow("if (!$T.of($L).contains($N.getProperty()))", SET, allowed, order)
                .addStatement("throw new $T($T.BAD_REQUEST, $S + $N.getProperty() + $S)",
                        RESPONSE_STATUS_EXCEPTION, HTTP_STATUS, PREFIX + methodName + " cannot be sorted by '", order,
                        "'; sortable: " + sortable)
                .endControlFlow()
                .endControlFlow();
    }

    /**
     * The controller's paging-input checks, for a controller with a {@code Pageable} operation.
     * Spring Data's resolver clamps what it reads: a size below 1 or unparseable becomes the default,
     * a size above its maximum page size becomes that maximum, and a negative page becomes 0. So the
     * raw {@code page} and {@code size} are checked, and any input the resolver changed is rejected.
     */
    static List<MethodSpec> restPagingCheckMethods() {
        ParameterSpec request = ParameterSpec.builder(WEB_REQUEST, "request").build();
        ParameterSpec operation = ParameterSpec.builder(String.class, "operation").build();
        MethodSpec input = MethodSpec.methodBuilder(PAGING_INPUT)
                .addJavadoc("A raw paging query parameter, or {@code null} when absent or blank, as Spring Data"
                        + " reads it.\n")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(Integer.class)
                .addParameter(request)
                .addParameter(String.class, "name")
                .addParameter(operation)
                .addParameter(String.class, "range")
                .addStatement("String value = request.getParameter(name)")
                .beginControlFlow("if (value == null || value.isBlank())")
                .addStatement("return null")
                .endControlFlow()
                .beginControlFlow("try")
                .addStatement("return Integer.parseInt(value)")
                .nextControlFlow("catch ($T e)", NumberFormatException.class)
                .addStatement("throw $L(operation + $S + name + $S + range + $S + value + $S)", BAD_REQUEST_METHOD,
                        ": ", " must be ", "; got '", "'")
                .endControlFlow()
                .build();
        MethodSpec badRequest = MethodSpec.methodBuilder(BAD_REQUEST_METHOD)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(RESPONSE_STATUS_EXCEPTION)
                .addParameter(String.class, "message")
                .addStatement("return new $T($T.BAD_REQUEST, $S + message)", RESPONSE_STATUS_EXCEPTION, HTTP_STATUS,
                        PREFIX)
                .build();
        String defaultSize = " (spring.data.web.pageable.default-page-size)";
        MethodSpec check = MethodSpec.methodBuilder(CHECK_PAGEABLE)
                .addJavadoc("Rejects a page or size Spring Data's resolver clamped or replaced, and one outside the"
                        + "\noperation's range, with 400. Nothing is clamped.\n")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addParameter(PAGEABLE, "pageable")
                .addParameter(request)
                .addParameter(operation)
                .addParameter(int.class, "maxSize")
                .addStatement("String pageRange = $S", "an integer from 0 to " + Integer.MAX_VALUE)
                .addStatement("Integer page = $L(request, $S, operation, pageRange)", PAGING_INPUT, PAGE_PARAM)
                .beginControlFlow("if (page != null && page < 0)")
                .addStatement("throw $L(operation + $S + pageRange + $S + page)", BAD_REQUEST_METHOD,
                        ": " + PAGE_PARAM + " must be ", "; got ")
                .endControlFlow()
                .addStatement("String sizeRange = $S + maxSize", "an integer from 1 to ")
                .addStatement("Integer size = $L(request, $S, operation, sizeRange)", PAGING_INPUT, SIZE_PARAM)
                .beginControlFlow("if (size != null && (size < 1 || size > maxSize))")
                .addStatement("throw $L(operation + $S + sizeRange + $S + size)", BAD_REQUEST_METHOD,
                        ": " + SIZE_PARAM + " must be ", "; got ")
                .endControlFlow()
                .beginControlFlow("if (size != null && size != pageable.getPageSize())")
                .addStatement("throw $L(operation + $S + size + $S + pageable.getPageSize()"
                                + " + $S + pageable.getPageSize())", BAD_REQUEST_METHOD,
                        ": " + SIZE_PARAM + " ", " is above the application's maximum page size ",
                        " (spring.data.web.pageable.max-page-size); pass a size from 1 to ")
                .endControlFlow()
                .beginControlFlow("if (size == null && pageable.isUnpaged() && maxSize != $L)", Integer.MAX_VALUE)
                .addStatement("throw $L(operation + $S + sizeRange)", BAD_REQUEST_METHOD, ": " + SIZE_PARAM
                        + " is required here: the application's default is unpaged; pass " + SIZE_PARAM + " as ")
                .endControlFlow()
                .beginControlFlow("if (size == null && pageable.isPaged() && pageable.getPageSize() > maxSize)")
                .addStatement("throw $L(operation + $S + pageable.getPageSize() + $S + maxSize + $S + sizeRange)",
                        BAD_REQUEST_METHOD, ": " + SIZE_PARAM + " is required here: the application's default page"
                                + " size ", defaultSize + " is above this operation's maximum of ",
                        "; pass " + SIZE_PARAM + " as ")
                .endControlFlow()
                .build();
        return List.of(check, input, badRequest);
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
