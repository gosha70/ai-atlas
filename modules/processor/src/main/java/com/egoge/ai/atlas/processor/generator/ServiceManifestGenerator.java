/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.processor.model.ServiceModel;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;

/**
 * Generates {@code META-INF/ai-atlas/service-manifest.properties} containing the qualified
 * names of every discovered {@code @AgenticExposed} service — even those whose methods are all
 * filtered out by channel or version.
 *
 * <p>The manifest is the single source of truth for the list of exposed services. The CLI
 * {@code inspect} command reads it from {@link com.egoge.ai.atlas.processor.driver.GenerationResult}
 * instead of reconstructing service names from generated wrapper files.
 */
public final class ServiceManifestGenerator {

    /** Class-output-relative path of the generated service manifest. */
    public static final String RESOURCE_PATH = "META-INF/ai-atlas/service-manifest.properties";

    private ServiceManifestGenerator() {}

    /**
     * Writes the qualified names of every service in the registry, one per line.
     *
     * @param services the processor's service registry (may be empty)
     * @param filer    JSR 269 filer for writing resources
     * @param messager JSR 269 messager for diagnostics
     */
    public static void generate(java.util.List<ServiceModel> services,
                                Filer filer, Messager messager) {
        try {
            var resource = filer.createResource(StandardLocation.CLASS_OUTPUT, "", RESOURCE_PATH);
            try (Writer writer = resource.openWriter()) {
                for (int i = 0; i < services.size(); i++) {
                    if (i > 0) {
                        writer.write('\n');
                    }
                    writer.write(services.get(i).serviceClassName().canonicalName());
                }
            }
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "[ai-atlas] Generated: " + RESOURCE_PATH);
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] Failed to write " + RESOURCE_PATH + ": " + e.getMessage());
        }
    }
}
