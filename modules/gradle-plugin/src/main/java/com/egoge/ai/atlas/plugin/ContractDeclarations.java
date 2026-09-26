/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.contract.ContractIr;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Whether a compile output declares an ai-atlas contract: whether any class carries
 * {@code @AgenticEntity} or {@code @AgenticExposed}, the two annotations that make javac invoke
 * the processor. Both are {@code RUNTIME}-retained, so a class carrying one holds its descriptor as
 * a {@code CONSTANT_Utf8} entry of its constant pool, which is read entry by entry.
 *
 * <p>The class files, not {@code META-INF/ai-atlas/api.ir.json}, tell whether the processor ran:
 * Gradle keeps the class files in step with the sources on every compilation, but may keep an
 * aggregating processor's resource from an earlier compilation, so an IR in the output can be
 * stale (FR-016).
 */
final class ContractDeclarations {

    /** Class-output-relative path of the IR the processor emits; a constant, inlined by javac. */
    static final String IR_PATH = ContractIr.RESOURCE_PATH;

    private static final String CLASS_SUFFIX = ".class";
    private static final int CLASS_MAGIC = 0xCAFEBABE;
    private static final Set<String> DECLARATIONS = Set.of(
            "Lcom/egoge/ai/atlas/annotations/AgenticEntity;",
            "Lcom/egoge/ai/atlas/annotations/AgenticExposed;");

    // Constant pool tags (JVMS 4.4) and the payload size of each fixed-size entry
    private static final int UTF8_TAG = 1;
    private static final int LONG_TAG = 5;
    private static final int DOUBLE_TAG = 6;
    private static final Map<Integer, Integer> FIXED_ENTRY_SIZES = Map.ofEntries(
            Map.entry(3, 4), Map.entry(4, 4), Map.entry(LONG_TAG, 8), Map.entry(DOUBLE_TAG, 8),
            Map.entry(7, 2), Map.entry(8, 2), Map.entry(9, 4), Map.entry(10, 4), Map.entry(11, 4),
            Map.entry(12, 4), Map.entry(15, 3), Map.entry(16, 2), Map.entry(17, 4), Map.entry(18, 4),
            Map.entry(19, 2), Map.entry(20, 2));

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

    /** Whether a {@code CONSTANT_Utf8} entry of the class's constant pool is a declaration's descriptor. */
    private static boolean carriesDeclaration(Path classFile) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(classFile)))) {
            if (in.readInt() != CLASS_MAGIC) {
                throw new IOException("not a class file");
            }
            in.skipNBytes(Integer.BYTES); // minor and major version
            int count = in.readUnsignedShort();
            for (int index = 1; index < count; index++) {
                int tag = in.readUnsignedByte();
                if (tag == UTF8_TAG) {
                    if (DECLARATIONS.contains(in.readUTF())) {
                        return true;
                    }
                    continue;
                }
                Integer size = FIXED_ENTRY_SIZES.get(tag);
                if (size == null) {
                    throw new IOException("unknown constant pool tag " + tag);
                }
                in.skipNBytes(size);
                if (tag == LONG_TAG || tag == DOUBLE_TAG) {
                    index++; // an 8-byte constant takes two entries
                }
            }
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the constant pool of " + classFile, e);
        }
    }
}
