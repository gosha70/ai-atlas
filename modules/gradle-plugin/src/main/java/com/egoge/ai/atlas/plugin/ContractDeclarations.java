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
 * the processor. Both are {@code RUNTIME}-retained, so a class or method carrying one lists it in
 * its {@code RuntimeVisibleAnnotations} attribute. Only those attributes count: the descriptor also
 * appears in the constant pool of a class that merely refers to the type, as a field type does.
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

    private static final String ANNOTATIONS_ATTRIBUTE = "RuntimeVisibleAnnotations";
    /** {@code element_value} tags (JVMS 4.7.16.1) whose value is a single constant pool index. */
    private static final String CONSTANT_ELEMENT_TAGS = "BCDFIJSZsc";

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

    /** Whether the class, or one of its methods, is annotated with a declaration (JVMS 4.1). */
    private static boolean carriesDeclaration(Path classFile) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(classFile)))) {
            if (in.readInt() != CLASS_MAGIC) {
                throw new IOException("not a class file");
            }
            in.skipNBytes(Integer.BYTES); // minor and major version
            String[] utf8 = readConstantPool(in);
            in.skipNBytes(3L * Short.BYTES); // access flags, this class, super class
            in.skipNBytes((long) Short.BYTES * in.readUnsignedShort()); // interfaces
            // Fields first: neither declaration targets them, but their table precedes the methods'
            return membersAnnotated(in, utf8) || membersAnnotated(in, utf8) || annotated(in, utf8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the class file " + classFile, e);
        }
    }

    /** The {@code CONSTANT_Utf8} entries of the constant pool by index; {@code null} at other indexes. */
    private static String[] readConstantPool(DataInputStream in) throws IOException {
        String[] utf8 = new String[in.readUnsignedShort()];
        for (int index = 1; index < utf8.length; index++) {
            int tag = in.readUnsignedByte();
            if (tag == UTF8_TAG) {
                utf8[index] = in.readUTF();
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
        return utf8;
    }

    /** Reads a {@code fields} or {@code methods} table up to the first member annotated with a declaration. */
    private static boolean membersAnnotated(DataInputStream in, String[] utf8) throws IOException {
        for (int count = in.readUnsignedShort(); count > 0; count--) {
            in.skipNBytes(3L * Short.BYTES); // access flags, name, descriptor
            if (annotated(in, utf8)) {
                return true;
            }
        }
        return false;
    }

    /** Reads an {@code attributes} table up to a {@code RuntimeVisibleAnnotations} holding a declaration. */
    private static boolean annotated(DataInputStream in, String[] utf8) throws IOException {
        for (int count = in.readUnsignedShort(); count > 0; count--) {
            String name = utf8[in.readUnsignedShort()];
            long length = Integer.toUnsignedLong(in.readInt());
            if (!ANNOTATIONS_ATTRIBUTE.equals(name)) {
                in.skipNBytes(length);
                continue;
            }
            for (int annotations = in.readUnsignedShort(); annotations > 0; annotations--) {
                if (DECLARATIONS.contains(utf8[in.readUnsignedShort()])) {
                    return true;
                }
                skipElementValuePairs(in);
            }
        }
        return false;
    }

    private static void skipElementValuePairs(DataInputStream in) throws IOException {
        for (int pairs = in.readUnsignedShort(); pairs > 0; pairs--) {
            in.skipNBytes(Short.BYTES); // element name
            skipElementValue(in);
        }
    }

    /** Skips an {@code element_value} (JVMS 4.7.16.1). */
    private static void skipElementValue(DataInputStream in) throws IOException {
        char tag = (char) in.readUnsignedByte();
        if (CONSTANT_ELEMENT_TAGS.indexOf(tag) >= 0) {
            in.skipNBytes(Short.BYTES);
        } else if (tag == 'e') {
            in.skipNBytes(2L * Short.BYTES); // type name, constant name
        } else if (tag == '@') {
            in.skipNBytes(Short.BYTES); // type
            skipElementValuePairs(in);
        } else if (tag == '[') {
            for (int values = in.readUnsignedShort(); values > 0; values--) {
                skipElementValue(in);
            }
        } else {
            throw new IOException("unknown element value tag " + tag);
        }
    }
}
