/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Set;

/**
 * A test processor that generates {@code shop.late.Late}, an {@code @AgenticEntity}, and
 * {@code shop.late.LateService}, an {@code @AgenticExposed} service, in the first round, so that
 * ai-atlas sees them only in the second round.
 */
final class LaterRoundSourceProcessor extends AbstractProcessor {

    private boolean generated;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (generated) {
            return false;
        }
        generated = true;
        write("shop.late.Late", """
                package shop.late;
                import com.egoge.ai.atlas.annotations.AgenticEntity;
                import com.egoge.ai.atlas.annotations.AgenticField;
                @AgenticEntity
                public class Late {
                    @AgenticField private String code;
                    public String getCode() { return code; }
                }
                """);
        write("shop.late.LateService", """
                package shop.late;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class LateService {
                    @AgenticExposed(description = "Late operation")
                    public String late() { return null; }
                }
                """);
        return false;
    }

    private void write(String name, String source) {
        try (Writer writer = processingEnv.getFiler().createSourceFile(name).openWriter()) {
            writer.write(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
