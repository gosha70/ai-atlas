/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link ProcessingEnvironment} decorator installed in {@code AgenticProcessor.init} that
 * captures the SHA-256 digest of every reserved (F2) {@code CLASS_OUTPUT} resource written through
 * it, without changing what any caller observes. Every method except {@link #getFiler()} delegates
 * untouched to the real environment; {@link #getFiler()} returns a {@link Filer} decorator whose
 * {@code createResource} is the only overridden method — every other {@code Filer} call, including
 * {@code createResource} for a path {@link ContractResources#isReserved} rejects, also delegates
 * untouched, so Gradle's own incremental-processing wrapper still observes each call exactly as
 * before.
 */
public final class ResourceRecorder implements ProcessingEnvironment {

    private final ProcessingEnvironment delegate;
    private final Filer filer;
    private final Map<String, String> digests = new ConcurrentHashMap<>();

    private ResourceRecorder(ProcessingEnvironment delegate) {
        this.delegate = delegate;
        this.filer = new RecordingFiler(delegate.getFiler());
    }

    /**
     * Wraps {@code env}, to be passed to {@code AbstractProcessor.init}.
     *
     * @param env the real processing environment
     * @return the wrapped environment, whose {@link #digests()} fills in as resources are closed
     */
    public static ResourceRecorder wrap(ProcessingEnvironment env) {
        return new ResourceRecorder(env);
    }

    /**
     * Every reserved artifact this compilation has written and closed so far, keyed by its
     * class-output-relative path, mapped to its lowercase hex SHA-256. A defensive copy, safe to
     * keep after this call.
     */
    public Map<String, String> digests() {
        return new TreeMap<>(digests);
    }

    /**
     * Writes {@code contract-resources.json} (D2.4, D2.5) from the effective configuration and the
     * digests recorded so far. Built before this write, the artifacts never list the manifest itself.
     *
     * @param configuration the compilation's effective configuration
     */
    public void writeManifest(EffectiveOptions configuration) {
        String json = new ContractResources.Manifest("declared", configuration, new TreeMap<>(digests())).write();
        try {
            FileObject resource = filer.createResource(StandardLocation.CLASS_OUTPUT, "", ContractResources.MANIFEST_PATH);
            try (Writer writer = resource.openWriter()) {
                writer.write(json);
            }
        } catch (IOException e) {
            delegate.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] Failed to write " + ContractResources.MANIFEST_PATH + ": " + e.getMessage());
        }
    }

    @Override public Map<String, String> getOptions() { return delegate.getOptions(); }
    @Override public Messager getMessager() { return delegate.getMessager(); }
    @Override public Filer getFiler() { return filer; }
    @Override public Elements getElementUtils() { return delegate.getElementUtils(); }
    @Override public Types getTypeUtils() { return delegate.getTypeUtils(); }
    @Override public SourceVersion getSourceVersion() { return delegate.getSourceVersion(); }
    @Override public Locale getLocale() { return delegate.getLocale(); }

    /** Delegates every {@link Filer} call untouched, except {@code createResource} for a reserved path. */
    private final class RecordingFiler implements Filer {

        private final Filer delegate;

        RecordingFiler(Filer delegate) {
            this.delegate = delegate;
        }

        @Override
        public JavaFileObject createSourceFile(CharSequence name, Element... originatingElements) throws IOException {
            return delegate.createSourceFile(name, originatingElements);
        }

        @Override
        public JavaFileObject createClassFile(CharSequence name, Element... originatingElements) throws IOException {
            return delegate.createClassFile(name, originatingElements);
        }

        @Override
        public FileObject createResource(JavaFileManager.Location location, CharSequence pkg,
                                          CharSequence relativeName, Element... originatingElements) throws IOException {
            FileObject resource = delegate.createResource(location, pkg, relativeName, originatingElements);
            String path = relativeName.toString();
            if (location == StandardLocation.CLASS_OUTPUT && pkg.length() == 0 && ContractResources.isReserved(path)) {
                return new DigestingFileObject(resource, path);
            }
            return resource;
        }

        @Override
        public FileObject getResource(JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName)
                throws IOException {
            return delegate.getResource(location, pkg, relativeName);
        }
    }

    /**
     * A {@link FileObject} that tees {@link #openOutputStream()} into a running SHA-256 and records
     * the resulting digest against {@code path} when the stream closes. {@link #openWriter()} funnels
     * through that same tee, as a UTF-8 {@link OutputStreamWriter}, so the digest matches exactly
     * what is persisted regardless of the platform's default charset. Every other method delegates
     * untouched.
     */
    private final class DigestingFileObject implements FileObject {

        private final FileObject delegate;
        private final String path;

        DigestingFileObject(FileObject delegate, String path) {
            this.delegate = delegate;
            this.path = path;
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            OutputStream real = delegate.openOutputStream();
            DigestOutputStream tee = new DigestOutputStream(real, sha256());
            return new OutputStream() {
                @Override public void write(int b) throws IOException { tee.write(b); }
                @Override public void write(byte[] b, int off, int len) throws IOException { tee.write(b, off, len); }
                @Override public void flush() throws IOException { tee.flush(); }

                @Override
                public void close() throws IOException {
                    tee.close();
                    digests.put(path, toHex(tee.getMessageDigest().digest()));
                }
            };
        }

        @Override
        public Writer openWriter() throws IOException {
            return new OutputStreamWriter(openOutputStream(), StandardCharsets.UTF_8);
        }

        @Override public URI toUri() { return delegate.toUri(); }
        @Override public String getName() { return delegate.getName(); }
        @Override public InputStream openInputStream() throws IOException { return delegate.openInputStream(); }

        @Override
        public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
            return delegate.openReader(ignoreEncodingErrors);
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            return delegate.getCharContent(ignoreEncodingErrors);
        }

        @Override public long getLastModified() { return delegate.getLastModified(); }
        @Override public boolean delete() { return delegate.delete(); }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
