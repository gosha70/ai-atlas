/*
 * Copyright (c) 2026 egoge.com. All Rights Reserved.
 * This software may be used and distributed according to the terms of the Apache-2.0 license.
 */
package com.egoge.ai.atlas.processor.driver;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link ForwardingJavaFileManager} that captures all generated output (sources, classes and
 * resources) in memory, never touching the filesystem.
 *
 * <p>Input-related calls (looking up source files, classpath entries) are delegated to the wrapped
 * {@link StandardJavaFileManager}. Output-related calls return {@link InMemoryJavaFileObject}
 * instances whose content is available after compilation completes.
 */
final class InMemoryJavaFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

    private final Map<Location, Map<String, InMemoryJavaFileObject>> outputs = new ConcurrentHashMap<>();

    InMemoryJavaFileManager(StandardJavaFileManager delegate) {
        super(delegate);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                JavaFileObject.Kind kind, FileObject sibling) {
        String ext = (kind == JavaFileObject.Kind.CLASS) ? ".class" : ".java";
        URI uri = URI.create("mem:///" + className.replace('.', '/') + ext);
        InMemoryJavaFileObject obj = new InMemoryJavaFileObject(uri, kind);
        outputs.computeIfAbsent(location, k -> new ConcurrentHashMap<>()).put(className, obj);
        return obj;
    }

    @Override
    public FileObject getFileForOutput(Location location, String packageName,
                                        String relativeName, FileObject sibling) {
        String key = packageName.isEmpty() ? relativeName
                : packageName.replace('.', '/') + "/" + relativeName;
        URI uri = URI.create("mem:///" + key);
        InMemoryJavaFileObject obj = new InMemoryJavaFileObject(uri, JavaFileObject.Kind.OTHER);
        outputs.computeIfAbsent(location, k -> new ConcurrentHashMap<>()).put(key, obj);
        return obj;
    }

    /**
     * Returns every file captured at the given location, keyed by its name and preserving
     * insertion order.
     *
     * @param location the output location (e.g. {@link StandardLocation#SOURCE_OUTPUT})
     * @return the captured files, in the order they were created
     */
    Map<String, InMemoryJavaFileObject> capturedAt(Location location) {
        Map<String, InMemoryJavaFileObject> loc = outputs.get(location);
        if (loc == null) {
            return Map.of();
        }
        return new LinkedHashMap<>(loc);
    }

    /**
     * Returns the underlying {@link StandardJavaFileManager} for operations (like
     * {@code getJavaFileObjectsFromPaths}) that are not on the {@link JavaFileManager} interface.
     */
    StandardJavaFileManager delegate() {
        return fileManager;
    }

    /**
     * Whether any output was captured at the given location.
     */
    boolean hasOutput(Location location) {
        Map<String, InMemoryJavaFileObject> loc = outputs.get(location);
        return loc != null && !loc.isEmpty();
    }
}
