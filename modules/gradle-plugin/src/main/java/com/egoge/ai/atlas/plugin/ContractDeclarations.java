/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.contract.ContractIr;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Whether a compile output declares an ai-atlas contract: whether any class carries
 * {@code @AgenticEntity} or {@code @AgenticExposed}, the two annotations that make javac invoke
 * the processor. Both are {@code RUNTIME}-retained, so a class carrying one holds its descriptor as
 * a constant, and Gradle keeps the class output in step with the sources on every compilation.
 *
 * <p>This, not the presence of {@code META-INF/ai-atlas/api.ir.json}, tells whether the processor
 * ran: Gradle may keep an aggregating processor's resource from an earlier compilation, so an IR
 * in the output can be stale (FR-016).
 */
final class ContractDeclarations {

    /** Class-output-relative path of the IR the processor emits; a constant, inlined by javac. */
    static final String IR_PATH = ContractIr.RESOURCE_PATH;

    private static final String CLASS_SUFFIX = ".class";
    /** A {@code CONSTANT_Utf8} entry: tag 1, a two-byte length, then the bytes. */
    private static final int UTF8_TAG = 1;
    private static final List<byte[]> DECLARATIONS = List.of(
            utf8Constant("Lcom/egoge/ai/atlas/annotations/AgenticEntity;"),
            utf8Constant("Lcom/egoge/ai/atlas/annotations/AgenticExposed;"));

    private ContractDeclarations() {
    }

    /**
     * @param classesDir a compile task's class output
     * @return whether any class in it carries {@code @AgenticEntity} or {@code @AgenticExposed}
     */
    static boolean declared(File classesDir) {
        if (!classesDir.isDirectory()) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(classesDir.toPath())) {
            return walk.filter(p -> p.getFileName().toString().endsWith(CLASS_SUFFIX))
                    .anyMatch(ContractDeclarations::carriesDeclaration);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan " + classesDir, e);
        }
    }

    private static boolean carriesDeclaration(Path classFile) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(classFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + classFile, e);
        }
        return DECLARATIONS.stream().anyMatch(constant -> indexOf(bytes, constant) >= 0);
    }

    private static byte[] utf8Constant(String descriptor) {
        byte[] text = descriptor.getBytes(StandardCharsets.US_ASCII);
        byte[] constant = new byte[text.length + 3];
        constant[0] = UTF8_TAG;
        constant[1] = (byte) (text.length >> 8);
        constant[2] = (byte) text.length;
        System.arraycopy(text, 0, constant, 3, text.length);
        return constant;
    }

    private static int indexOf(byte[] bytes, byte[] pattern) {
        outer:
        for (int i = 0; i <= bytes.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (bytes[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
