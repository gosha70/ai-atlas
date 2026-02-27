/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.processor.util;

import ai.adam.annotations.AgentVisible;
import ai.adam.processor.model.FieldModel;
import com.palantir.javapoet.TypeName;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import java.util.List;

/**
 * Scans a TypeElement for fields annotated with {@code @AgentVisible}
 * and converts them to {@link FieldModel} instances.
 */
public final class FieldScanner {

    private FieldScanner() {
    }

    /**
     * Scans direct fields (no superclass walking — deferred to Cycle 2)
     * of the given type element for {@code @AgentVisible} annotations.
     *
     * @return list of FieldModel for each annotated field
     */
    public static List<FieldModel> scan(TypeElement typeElement) {
        return typeElement.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.FIELD)
                .filter(e -> e.getAnnotation(AgentVisible.class) != null)
                .map(e -> {
                    var field = (VariableElement) e;
                    var annotation = field.getAnnotation(AgentVisible.class);
                    return new FieldModel(
                            field.getSimpleName().toString(),
                            TypeName.get(field.asType()),
                            annotation.description(),
                            annotation.sensitive()
                    );
                })
                .toList();
    }
}
