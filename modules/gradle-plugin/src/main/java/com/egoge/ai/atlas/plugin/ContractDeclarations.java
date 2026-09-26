/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.contract.ContractIr;
import org.gradle.api.logging.Logging;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Whether a compile output declares an ai-atlas contract: whether any class carries
 * {@code @AgenticEntity} or {@code @AgenticExposed}, the two annotations that make javac invoke
 * the processor. Both are {@code RUNTIME}-retained, so a class or member carrying one lists it in
 * its {@code RuntimeVisibleAnnotations} attribute. Only those attributes count: the descriptor also
 * appears in the constant pool of a class that merely refers to the type, as a field type does.
 *
 * <p>A local or anonymous class, or a class nested in one, does not count, nor do its members:
 * javac never reports them to a processor, so the processor emits nothing for them. Such a class
 * has an {@code EnclosingMethod} attribute, or the {@code InnerClasses} entry of the class or of a
 * class enclosing it has no outer class or no simple name (JVMS 4.7.6, 4.7.7).
 *
 * <p>The class files, not {@code META-INF/ai-atlas/api.ir.json}, tell whether the processor ran:
 * Gradle keeps the class files in step with the sources on every compilation, but may keep an
 * aggregating processor's resource from an earlier compilation, so an IR in the output can be
 * stale (FR-016).
 *
 * <p>A class file in a format this reader does not know, such as one with a constant pool tag of a
 * newer class-file version, cannot tell. It is reported as a warning and counts as declaring
 * nothing: any other class that declares still makes the output declare, and otherwise the empty
 * contract is checked against the baseline, which can only fail the build naming the baseline,
 * never pass a removed contract silently.
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
    private static final String ENCLOSING_METHOD_ATTRIBUTE = "EnclosingMethod";
    private static final String INNER_CLASSES_ATTRIBUTE = "InnerClasses";
    /** {@code element_value} tags (JVMS 4.7.16.1) whose value is a single constant pool index. */
    private static final String CONSTANT_ELEMENT_TAGS = "BCDFIJSZsc";

    // Constant pool tags (JVMS 4.4) and the payload size of each fixed-size entry
    private static final int UTF8_TAG = 1;
    private static final int LONG_TAG = 5;
    private static final int DOUBLE_TAG = 6;
    private static final int CLASS_TAG = 7;
    private static final Map<Integer, Integer> FIXED_ENTRY_SIZES = Map.ofEntries(
            Map.entry(3, 4), Map.entry(4, 4), Map.entry(LONG_TAG, 8), Map.entry(DOUBLE_TAG, 8),
            Map.entry(CLASS_TAG, 2), Map.entry(8, 2), Map.entry(9, 4), Map.entry(10, 4), Map.entry(11, 4),
            Map.entry(12, 4), Map.entry(15, 3), Map.entry(16, 2), Map.entry(17, 4), Map.entry(18, 4),
            Map.entry(19, 2), Map.entry(20, 2));

    private ContractDeclarations() {
    }

    /**
     * @param classesDir a compile task's class output
     * @return whether any class in it that javac reports to processors carries {@code @AgenticEntity}
     *         or {@code @AgenticExposed}; a class file this reader cannot tell about is logged as a
     *         warning and counts as declaring nothing
     */
    static boolean declared(File classesDir) {
        return declared(classesDir, Logging.getLogger(ContractDeclarations.class)::warn);
    }

    /**
     * @param classesDir a compile task's class output
     * @param warnings   receives one warning for each class file this reader cannot tell about
     * @return as {@link #declared(File)}
     */
    static boolean declared(File classesDir, Consumer<String> warnings) {
        if (!classesDir.isDirectory()) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(classesDir.toPath())) {
            return walk.filter(p -> p.getFileName().toString().endsWith(CLASS_SUFFIX))
                    .anyMatch(classFile -> carriesDeclaration(classFile, warnings));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan " + classesDir, e);
        }
    }

    /** Whether the class, or one of its members, is annotated with a declaration javac reports (JVMS 4.1). */
    private static boolean carriesDeclaration(Path classFile, Consumer<String> warnings) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(classFile)))) {
            if (in.readInt() != CLASS_MAGIC) {
                throw new IOException("not a class file");
            }
            in.skipNBytes(Integer.BYTES); // minor and major version
            ConstantPool pool = readConstantPool(in);
            in.skipNBytes(Short.BYTES); // access flags
            String thisClass = pool.className(in.readUnsignedShort());
            in.skipNBytes(Short.BYTES); // super class
            in.skipNBytes((long) Short.BYTES * in.readUnsignedShort()); // interfaces
            // All tables are read: the attributes that tell a local class follow the fields and methods
            boolean fields = membersAnnotated(in, pool);
            boolean methods = membersAnnotated(in, pool);
            Attributes attributes = readAttributes(in, pool);
            return (fields || methods || attributes.annotated) && !attributes.local(thisClass);
        } catch (UnknownFormatException e) {
            warnings.accept("Cannot tell whether " + classFile + " declares an ai-atlas contract (" + e.getMessage()
                    + "); it counts as declaring nothing");
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the class file " + classFile, e);
        }
    }

    private static ConstantPool readConstantPool(DataInputStream in) throws IOException {
        ConstantPool pool = new ConstantPool(in.readUnsignedShort());
        for (int index = 1; index < pool.utf8.length; index++) {
            int tag = in.readUnsignedByte();
            if (tag == UTF8_TAG) {
                pool.utf8[index] = in.readUTF();
                continue;
            }
            if (tag == CLASS_TAG) {
                pool.classNames[index] = in.readUnsignedShort();
                continue;
            }
            Integer size = FIXED_ENTRY_SIZES.get(tag);
            if (size == null) {
                throw new UnknownFormatException("unknown constant pool tag " + tag);
            }
            in.skipNBytes(size);
            if (tag == LONG_TAG || tag == DOUBLE_TAG) {
                index++; // an 8-byte constant takes two entries
            }
        }
        return pool;
    }

    /** Reads a {@code fields} or {@code methods} table; whether a member is annotated with a declaration. */
    private static boolean membersAnnotated(DataInputStream in, ConstantPool pool) throws IOException {
        boolean annotated = false;
        for (int count = in.readUnsignedShort(); count > 0; count--) {
            in.skipNBytes(3L * Short.BYTES); // access flags, name, descriptor
            annotated |= readAttributes(in, pool).annotated;
        }
        return annotated;
    }

    /** Reads an {@code attributes} table, of the class or of a member. */
    private static Attributes readAttributes(DataInputStream in, ConstantPool pool) throws IOException {
        Attributes attributes = new Attributes();
        for (int count = in.readUnsignedShort(); count > 0; count--) {
            String name = pool.utf8(in.readUnsignedShort());
            long length = Integer.toUnsignedLong(in.readInt());
            if (ANNOTATIONS_ATTRIBUTE.equals(name)) {
                attributes.annotated |= annotated(body(in, length), pool);
            } else if (INNER_CLASSES_ATTRIBUTE.equals(name)) {
                readInnerClasses(body(in, length), pool, attributes.nesting);
            } else {
                attributes.enclosingMethod |= ENCLOSING_METHOD_ATTRIBUTE.equals(name);
                in.skipNBytes(length);
            }
        }
        return attributes;
    }

    /** A copy of an attribute's body, so reading it may stop anywhere without misaligning the class file. */
    private static DataInputStream body(DataInputStream in, long length) throws IOException {
        if (length > Integer.MAX_VALUE) {
            throw new IOException("attribute of " + length + " bytes");
        }
        byte[] body = in.readNBytes((int) length);
        if (body.length < length) {
            throw new EOFException();
        }
        return new DataInputStream(new ByteArrayInputStream(body));
    }

    /** Whether a {@code RuntimeVisibleAnnotations} body holds a declaration. */
    private static boolean annotated(DataInputStream in, ConstantPool pool) throws IOException {
        for (int annotations = in.readUnsignedShort(); annotations > 0; annotations--) {
            if (DECLARATIONS.contains(pool.utf8(in.readUnsignedShort()))) {
                return true;
            }
            skipElementValuePairs(in);
        }
        return false;
    }

    /** Maps each class an {@code InnerClasses} body lists to how it is nested. */
    private static void readInnerClasses(DataInputStream in, ConstantPool pool, Map<String, Nesting> nesting)
            throws IOException {
        for (int classes = in.readUnsignedShort(); classes > 0; classes--) {
            String inner = pool.className(in.readUnsignedShort());
            String outer = pool.className(in.readUnsignedShort());
            boolean named = in.readUnsignedShort() != 0;
            in.skipNBytes(Short.BYTES); // access flags
            nesting.put(inner, new Nesting(outer, named));
        }
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
            throw new UnknownFormatException("unknown element value tag " + tag);
        }
    }

    /** The {@code CONSTANT_Utf8} and {@code CONSTANT_Class} entries of a constant pool, by index. */
    private static final class ConstantPool {

        private final String[] utf8;
        private final int[] classNames;

        ConstantPool(int count) {
            utf8 = new String[count];
            classNames = new int[count];
        }

        /** The {@code CONSTANT_Utf8} at an index; {@code null} if there is none. */
        String utf8(int index) {
            return index < utf8.length ? utf8[index] : null;
        }

        /** The internal name of the {@code CONSTANT_Class} at an index; {@code null} if there is none. */
        String className(int index) {
            return index < classNames.length ? utf8(classNames[index]) : null;
        }
    }

    /** What an {@code attributes} table tells. */
    private static final class Attributes {

        private boolean annotated;
        private boolean enclosingMethod;
        private final Map<String, Nesting> nesting = new HashMap<>();

        /** Whether the class is local or anonymous, or nested in a local or anonymous class. */
        boolean local(String thisClass) {
            if (enclosingMethod) {
                return true;
            }
            Set<String> seen = new HashSet<>();
            String current = thisClass;
            Nesting nested;
            while (current != null && seen.add(current) && (nested = nesting.get(current)) != null) {
                if (nested.outer() == null || !nested.named()) {
                    return true;
                }
                current = nested.outer();
            }
            return false;
        }
    }

    /** An {@code InnerClasses} entry: the outer class, if the class is a member, and whether it has a name. */
    private record Nesting(String outer, boolean named) {
    }

    /** A class file in a format this reader does not know, so it cannot tell whether the class declares. */
    private static final class UnknownFormatException extends IOException {

        UnknownFormatException(String message) {
            super(message);
        }
    }
}
