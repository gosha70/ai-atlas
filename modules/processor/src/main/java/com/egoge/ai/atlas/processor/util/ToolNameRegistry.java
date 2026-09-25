/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.util;

import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;

import javax.annotation.processing.Messager;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collects the effective MCP tool name of every active AI-channel method across the whole
 * compilation and reports every method whose tool name another method shares (FR-009, FR-010) —
 * {@code McpToolGenerator} runs per service, so no single generator call sees them all.
 */
public final class ToolNameRegistry {

    /** An active AI-channel method, keyed in {@link #tools} by its effective tool name. */
    private record Site(String declaration, ExecutableElement element) { }

    private final Map<String, List<Site>> tools = new LinkedHashMap<>();
    private final Set<ExecutableElement> reported = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Records the tool {@code McpToolGenerator} generates for {@code model}, if any. */
    public void record(TypeElement serviceType, ExecutableElement method, MethodModel model, int apiMajor) {
        if (!model.channels().contains("AI") || !VersionSelector.isActive(model, apiMajor)) {
            return;
        }
        String declaration = serviceType.getQualifiedName() + "#" + model.methodName();
        tools.computeIfAbsent(model.toolName(), k -> new ArrayList<>()).add(new Site(declaration, method));
    }

    /** Reports an ERROR on each shared-name method not yet reported, naming the other declarations. */
    public void reportCollisions(Messager messager) {
        for (var entry : tools.entrySet()) {
            List<Site> sites = entry.getValue();
            if (sites.size() < 2) {
                continue;
            }
            for (Site site : sites) {
                if (!reported.add(site.element())) {
                    continue;
                }
                List<String> others = new ArrayList<>();
                for (Site other : sites) {
                    if (other != site) {
                        others.add(other.declaration());
                    }
                }
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "[ai-atlas] MCP tool name '" + entry.getKey() + "' of " + site.declaration()
                                + " is also used by " + String.join(", ", others)
                                + " — set an explicit toolName on @AgenticExposed to make it unique",
                        site.element());
            }
        }
    }
}
