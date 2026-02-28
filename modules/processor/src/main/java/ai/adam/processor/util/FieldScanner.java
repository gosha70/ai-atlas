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
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Scans a TypeElement and its superclass chain for fields annotated
 * with {@code @AgentVisible} and converts them to {@link FieldModel} instances.
 */
public final class FieldScanner {

    private FieldScanner() {
    }

    /**
     * Scans the given type element and its entire superclass chain
     * for {@code @AgentVisible} fields. Fields from supertypes appear
     * before subtype fields. Duplicate field names are skipped (subtype wins).
     *
     * @return list of FieldModel for each annotated field
     */
    public static List<FieldModel> scan(TypeElement typeElement) {
        Set<String> seenFieldNames = new LinkedHashSet<>();
        List<FieldModel> allFields = new ArrayList<>();

        // Walk superclass chain (top-down: collect supertypes first, then reverse)
        List<TypeElement> hierarchy = new ArrayList<>();
        TypeElement current = typeElement;
        while (current != null) {
            hierarchy.add(current);
            current = getSuperclassElement(current);
        }

        // Process from top of hierarchy down (so superclass fields come first)
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            for (var enclosed : hierarchy.get(i).getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.FIELD) continue;
                AgentVisible annotation = enclosed.getAnnotation(AgentVisible.class);
                if (annotation == null) continue;

                String fieldName = enclosed.getSimpleName().toString();
                if (seenFieldNames.add(fieldName)) {
                    var field = (VariableElement) enclosed;
                    allFields.add(new FieldModel(
                            fieldName,
                            TypeName.get(field.asType()),
                            annotation.description(),
                            annotation.sensitive()
                    ));
                }
            }
        }

        return allFields;
    }

    /**
     * Resolves the superclass TypeElement, or null if the superclass is
     * {@code java.lang.Object} or absent.
     */
    private static TypeElement getSuperclassElement(TypeElement typeElement) {
        TypeMirror superclass = typeElement.getSuperclass();
        if (superclass.getKind() == TypeKind.NONE) {
            return null;
        }
        if (superclass instanceof DeclaredType declaredType) {
            var element = declaredType.asElement();
            if (element instanceof TypeElement superElement) {
                String qualifiedName = superElement.getQualifiedName().toString();
                if ("java.lang.Object".equals(qualifiedName)) {
                    return null;
                }
                return superElement;
            }
        }
        return null;
    }
}
