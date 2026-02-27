/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.processor;

import ai.adam.annotations.AgentVisible;
import ai.adam.annotations.AgentVisibleClass;
import ai.adam.processor.generator.DtoGenerator;
import ai.adam.processor.model.EntityModel;
import ai.adam.processor.util.FieldScanner;
import ai.adam.processor.util.PiiDetector;
import com.google.auto.service.AutoService;
import com.palantir.javapoet.ClassName;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.Set;

/**
 * JSR 269 annotation processor for the AI-ADAM framework.
 * Scans for {@code @AgentVisibleClass} types and generates PII-safe DTO records
 * containing only {@code @AgentVisible} fields.
 *
 * <p>Registered as AGGREGATING for Gradle incremental builds.
 */
@AutoService(Processor.class)
@SupportedAnnotationTypes({
        "ai.adam.annotations.AgentVisibleClass",
        "ai.adam.annotations.AgenticExposed"
})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class AgenticProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }

        // Process @AgentVisibleClass entities → generate DTOs
        for (var element : roundEnv.getElementsAnnotatedWith(AgentVisibleClass.class)) {
            if (element.getKind() != ElementKind.CLASS) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "@AgentVisibleClass can only be applied to classes",
                        element
                );
                continue;
            }

            TypeElement typeElement = (TypeElement) element;
            processEntity(typeElement);
        }

        return true;
    }

    private void processEntity(TypeElement typeElement) {
        var annotation = typeElement.getAnnotation(AgentVisibleClass.class);
        var fields = FieldScanner.scan(typeElement);

        // Emit PII warnings for unannotated fields
        emitPiiWarnings(typeElement);

        if (fields.isEmpty()) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.WARNING,
                    "@AgentVisibleClass on " + typeElement.getSimpleName()
                            + " has no @AgentVisible fields — no DTO will be generated",
                    typeElement
            );
            return;
        }

        // Resolve DTO name
        String simpleName = typeElement.getSimpleName().toString();
        String dtoName = annotation.dtoName().isEmpty()
                ? simpleName + "Dto"
                : annotation.dtoName();

        // Resolve DTO package
        String sourcePackage = processingEnv.getElementUtils()
                .getPackageOf(typeElement).getQualifiedName().toString();
        String dtoPackage = annotation.packageName().isEmpty()
                ? sourcePackage + ".generated"
                : annotation.packageName();

        ClassName sourceClassName = ClassName.get(typeElement);

        EntityModel model = new EntityModel(sourceClassName, dtoName, dtoPackage, fields);

        DtoGenerator.generate(model, processingEnv.getFiler(), processingEnv.getMessager());
    }

    private void emitPiiWarnings(TypeElement typeElement) {
        for (var enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD
                    && enclosed.getAnnotation(AgentVisible.class) == null) {
                PiiDetector.check(
                        enclosed.getSimpleName().toString(),
                        enclosed,
                        processingEnv.getMessager()
                );
            }
        }
    }
}
