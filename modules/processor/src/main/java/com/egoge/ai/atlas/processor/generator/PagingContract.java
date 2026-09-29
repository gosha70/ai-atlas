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
import java.util.Collection;
import java.util.List;

/**
 * <strong>Spike (Phase 5, epic #23 &sect;8).</strong> How an operation's collection result is
 * bounded, as {@link CollectionsOption} recognised it, and the code the generated wrappers emit for
 * it. Every index is a position in the method's parameters, {@code -1} when absent.
 *
 * @param envelope    whether the service returns a Spring Data {@code Page} or {@code Slice}
 * @param pageable    the Spring Data {@code Pageable} parameter
 * @param limit       the {@code @AgenticParam(paging = LIMIT)} parameter
 * @param cursor      the {@code @AgenticParam(paging = CURSOR)} parameter, only with a limit
 * @param maxResults  the declared bound, {@code -1} when none
 * @param elementType the Page or Slice element type, or {@code null} when raw or not an envelope
 */
public record PagingContract(Envelope envelope, int pageable, int limit, int cursor, int maxResults,
                             TypeName elementType) {

    /** The MCP input and REST query parameter carrying the zero-based page number. */
    public static final String PAGE_PARAM = "page";
    /** The MCP input and REST query parameter carrying the page size. */
    public static final String SIZE_PARAM = "size";
    /** The REST query parameter carrying sort orders, as Spring Data's resolver reads it. */
    public static final String SORT_PARAM = "sort";

    private static final ClassName GENERATED = ClassName.get("javax.annotation.processing", "Generated");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName PAGE_REQUEST = ClassName.bestGuess(CollectionsOption.PAGE_REQUEST);
    private static final String PAGE_RESULT = "PageResult";
    private static final String SLICE_RESULT = "SliceResult";

    /** What the service returns around its elements. */
    public enum Envelope {
        /** A plain collection, iterable or array. */
        NONE,
        /** A Spring Data {@code Page}: a slice with totals. */
        PAGE,
        /** A Spring Data {@code Slice}: no totals, only whether a next slice exists. */
        SLICE
    }

    /** Whether a wrapper method's parameter at {@code index} is replaced by page and size inputs. */
    public boolean replaces(int index) {
        return index == pageable;
    }

    /**
     * The nested envelope records a wrapper class declares for its methods' contracts, empty when
     * none of them returns a Page or Slice.
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
        List<TypeSpec> records = new java.util.ArrayList<>();
        if (page) {
            records.add(record(PAGE_RESULT, "A page of results, with the totals the service computed", t,
                    concat(common, ParameterSpec.builder(long.class, "totalElements").build(),
                            ParameterSpec.builder(int.class, "totalPages").build())));
        }
        if (slice) {
            records.add(record(SLICE_RESULT, "A slice of results; hasNext says whether another slice exists", t,
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

    private static List<ParameterSpec> concat(List<ParameterSpec> head, ParameterSpec... tail) {
        List<ParameterSpec> all = new java.util.ArrayList<>(head);
        all.addAll(List.of(tail));
        return all;
    }

    /** The wrapper's return type for an envelope, {@code enclosing.PageResult<element>}. */
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

    /** The argument the MCP wrapper passes for the Pageable: a request built from its page and size inputs. */
    static CodeBlock pageRequestArgument() {
        return CodeBlock.of("$T.of($L == null ? 0 : $L, $L)", PAGE_REQUEST, PAGE_PARAM, PAGE_PARAM, SIZE_PARAM);
    }

    /** One sentence for the MCP tool description, so a model knows how to page or what bound applies. */
    String toolGuidance(List<String> parameterNames) {
        if (pageable != -1) {
            return "Results are paged: pass size (at least 1) and optionally page (zero-based, default 0)"
                    + (envelope == Envelope.PAGE ? "; the result carries hasNext, totalElements and totalPages."
                    : envelope == Envelope.SLICE ? "; the result carries hasNext." : ".");
        }
        if (limit != -1) {
            return "Returns at most '" + parameterNames.get(limit) + "' results"
                    + (cursor != -1 ? "; pass '" + parameterNames.get(cursor) + "' to continue after a previous call."
                    : ".");
        }
        if (maxResults != -1) {
            return "Returns at most " + maxResults + " results.";
        }
        return "";
    }
}
