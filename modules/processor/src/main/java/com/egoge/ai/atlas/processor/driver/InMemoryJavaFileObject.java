/*
 * Copyright (c) 2026 egoge.com. All Rights Reserved.
 * This software may be used and distributed according to the terms of the Apache-2.0 license.
 */
package com.egoge.ai.atlas.processor.driver;

import javax.tools.SimpleJavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * A {@link javax.tools.JavaFileObject} backed by a byte array, for capturing generated sources,
 * classes and resources during an in-memory dry-run compilation.
 *
 * <p>javac writes output through the file manager's {@code openOutputStream()}; the bytes are held
 * here and can be retrieved after the compilation task completes.
 */
final class InMemoryJavaFileObject extends SimpleJavaFileObject {

    private final ByteArrayOutputStream content = new ByteArrayOutputStream();

    /**
     * @param uri  a unique URI for this file object — the scheme is irrelevant as long as it does
     *             not collide with any filesystem URI used in the same compilation
     * @param kind the kind of file being captured
     */
    InMemoryJavaFileObject(URI uri, Kind kind) {
        super(uri, kind);
    }

    @Override
    public OutputStream openOutputStream() {
        return content;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return content.toString(StandardCharsets.UTF_8);
    }

    /** The raw bytes written by javac or the annotation processor. */
    byte[] getBytes() {
        return content.toByteArray();
    }

    /**
     * Returns the textual content, decoded as UTF-8.
     *
     * @throws java.nio.charset.CharacterCodingException (wrapped) if the bytes are not valid UTF-8
     */
    String getContent() throws IOException {
        return content.toString(StandardCharsets.UTF_8);
    }
}
