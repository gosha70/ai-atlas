/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.util;

import com.egoge.ai.atlas.processor.contract.ContractIr;
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
 * Collects the (HTTP method, path) each API-channel method maps to across the whole compilation
 * and reports every method whose mapping another method shares — the generated controllers
 * would otherwise fail at application startup with an ambiguous mapping.
 */
public final class RestMappingRegistry {

    /** A method's generated REST mapping, keyed in {@link #mappings} by "HTTP-method path". */
    private record Site(String declaration, ExecutableElement element) { }

    private final Map<String, List<Site>> mappings = new LinkedHashMap<>();
    private final Set<ExecutableElement> reported = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Records the mapping {@code RestControllerGenerator} generates for {@code model}, if any: the
     * Contract IR's {@code rest}, which the controller and the OpenAPI document read too. Paths
     * that differ only by variable names, {@code /{id}} and {@code /{orderId}}, are one route.
     *
     * @param rest the operation's REST mapping, or {@code null} when it is not on the API channel
     */
    public void record(TypeElement serviceType, ExecutableElement method, MethodModel model,
                       ContractIr.Rest rest, String apiBasePath, int apiMajor) {
        if (rest == null || !model.channels().contains("API") || !VersionSelector.isActive(model, apiMajor)) {
            return;
        }
        String route = rest.routeKey();
        int space = route.indexOf(' ');
        String key = route.substring(0, space) + " " + apiBasePath + "/v" + apiMajor + route.substring(space + 1);
        String declaration = serviceType.getQualifiedName() + "#" + model.methodName();
        mappings.computeIfAbsent(key, k -> new ArrayList<>())
                .add(new Site(declaration, method));
    }

    /** Reports an ERROR on each shared-mapping method not yet reported, naming the other declarations. */
    public void reportDuplicates(Messager messager) {
        for (var entry : mappings.entrySet()) {
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
                        "[ai-atlas] REST mapping " + entry.getKey() + " of " + site.declaration()
                                + " is also mapped by " + String.join(", ", others)
                                + " — the generated controllers would fail at startup with an ambiguous"
                                + " mapping; rename the method or remove it from the API channel",
                        site.element());
            }
        }
    }
}
