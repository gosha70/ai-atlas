/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.annotations.AgenticExposed;
import com.egoge.ai.atlas.processor.rest.RestOperation;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IrBuilder#write} checks every operation it built with {@link IrConsistency}, as
 * {@link IrJson} checks every document it reads: an inconsistent operation is an ERROR on its
 * method, naming the operation and the contradiction, and no IR is written. The builder is
 * consistent today, so a test processor records an operation under a mapping whose path variable
 * names no path parameter.
 */
class IrBuilderConsistencyTest {

    /** Noted when the IR path can still be created after {@code write}: the Filer refuses a second creation. */
    private static final String UNWRITTEN = "the IR was not written";
    private static final String SERVICE = """
            package shop;
            import com.egoge.ai.atlas.annotations.AgenticExposed;
            public class OrderService {
                @AgenticExposed(description = "Find an order") public String find(Long id) { return null; }
            }
            """;

    @Test
    void anInconsistentOperationIsAnErrorOnItsMethodAndNoIrIsWritten() {
        Compilation compilation = compile(new RestOperation("GET", "orders", "/{x}", 200, List.of("QUERY"), null, null));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(compilation.errors()).singleElement().satisfies(error -> {
            assertThat(error.getMessage(Locale.ROOT)).contains("The Contract IR the processor built is inconsistent:"
                    + " operation 'shop.OrderService#find(java.lang.Long)': the path variable {x} of /orders/{x}"
                    + " names no PATH parameter");
            assertThat(error.getLineNumber()).isEqualTo(4);
        });
        assertThat(notes(compilation)).contains(UNWRITTEN);
    }

    @Test
    void aConsistentOperationIsWritten() {
        Compilation compilation = compile(new RestOperation("GET", "orders", "/{id}", 200, List.of("PATH"), null, null));

        assertThat(compilation.status()).as(compilation.diagnostics().toString()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(compilation.generatedFile(StandardLocation.CLASS_OUTPUT, "", ContractIr.RESOURCE_PATH)).isPresent();
        assertThat(notes(compilation)).doesNotContain(UNWRITTEN);
    }

    private static List<String> notes(Compilation compilation) {
        return compilation.notes().stream().map(note -> note.getMessage(Locale.ROOT)).toList();
    }

    private static Compilation compile(RestOperation mapping) {
        return javac().withProcessors(new RecordWith(mapping))
                .compile(JavaFileObjects.forSourceString("shop.OrderService", SERVICE));
    }

    /** Records each {@code @AgenticExposed} method under one fixed REST mapping, then writes the IR. */
    @SupportedAnnotationTypes("com.egoge.ai.atlas.annotations.AgenticExposed")
    private static final class RecordWith extends AbstractProcessor {

        private final RestOperation mapping;

        RecordWith(RestOperation mapping) {
            this.mapping = mapping;
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(java.util.Set<? extends TypeElement> annotations, RoundEnvironment round) {
            if (round.processingOver()) {
                return false;
            }
            IrBuilder ir = new IrBuilder(processingEnv, (entity, field) -> List.of());
            for (ExecutableElement method : ElementFilter.methodsIn(round.getElementsAnnotatedWith(AgenticExposed.class))) {
                ir.addOperation((TypeElement) method.getEnclosingElement(), method, null, mapping);
            }
            ir.write("/api", 1);
            try {
                processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", ContractIr.RESOURCE_PATH);
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, UNWRITTEN);
            } catch (IOException e) {
                // write created it
            }
            return false;
        }
    }
}
