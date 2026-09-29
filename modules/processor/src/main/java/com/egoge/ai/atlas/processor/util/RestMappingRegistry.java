/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.util;

import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;
import com.egoge.ai.atlas.processor.rest.RestOperation;

import javax.annotation.processing.Messager;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Collects the route each API-channel method maps to across the whole compilation, from the
 * resolved {@link RestOperation} the controller and the OpenAPI document read too, and reports:
 * <ul>
 *   <li>an ERROR on each method whose route another method shares, variable names aside, as Spring
 *       matches them: {@code /{id}} and {@code /{orderId}} are one route. The generated controllers
 *       would otherwise fail at application startup with an ambiguous mapping;</li>
 *   <li>an ERROR on each of two routes that match the same requests while neither is more
 *       specific, such as {@code /{a}/items} and {@code /orders/{b}}: Spring cannot choose between
 *       them at request time;</li>
 *   <li>a NOTE on a route with a literal segment where another has a variable, such as
 *       {@code /orders/active} beside {@code /orders/{id}}: Spring prefers the literal, which clients
 *       may not expect.</li>
 * </ul>
 */
public final class RestMappingRegistry {

    private static final String VARIABLE = "{}";
    /** A package qualifier, left out of a declaration's parameter types. */
    private static final Pattern QUALIFIER = Pattern.compile("\\b(?:[a-z_$][\\w$]*\\.)+(?=[A-Za-z_$])");

    /**
     * A method's generated route; {@code route} is written as declared, the map key with {@code {}}
     * variables. The declaration names the parameter types, so overloads are told apart.
     */
    private record Site(String declaration, ExecutableElement element, String route) { }

    private final Map<String, List<Site>> mappings = new LinkedHashMap<>();
    private final Set<ExecutableElement> reported = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> reportedOverlaps = new HashSet<>();

    /**
     * Records the route {@code RestControllerGenerator} generates for {@code model}, if any.
     *
     * @param rest the method's resolved REST mapping, or {@code null} when it is not on the API channel
     */
    public void record(TypeElement serviceType, ExecutableElement method, MethodModel model, RestOperation rest,
                       String apiBasePath, int apiMajor) {
        if (rest == null || !model.channels().contains("API") || !VersionSelector.isActive(model, apiMajor)) {
            return;
        }
        String path = apiBasePath + "/v" + apiMajor + rest.fullPath();
        String declaration = serviceType.getQualifiedName() + "#" + model.methodName() + method.getParameters()
                .stream().map(p -> QUALIFIER.matcher(p.asType().toString()).replaceAll(""))
                .collect(Collectors.joining(", ", "(", ")"));
        mappings.computeIfAbsent(RestOperation.routeKey(rest.httpMethod(), path), k -> new ArrayList<>())
                .add(new Site(declaration, method, rest.httpMethod() + " " + path));
    }

    /**
     * Reports an ERROR on each shared-route method not yet reported, naming the other declarations,
     * and the overlaps between distinct routes described above, each once.
     */
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
                        others.add(other.declaration() + (other.route().equals(site.route()) ? "" : " (" + other.route() + ")"));
                    }
                }
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "[ai-atlas] REST mapping " + site.route() + " of " + site.declaration()
                                + " is also mapped by " + String.join(", ", others)
                                + " — the generated controllers would fail at startup with an ambiguous"
                                + " mapping; rename the method, change its @Rest path, or remove it from the API"
                                + " channel",
                        site.element());
            }
        }
        reportOverlaps(messager);
    }

    private void reportOverlaps(Messager messager) {
        List<String> keys = new ArrayList<>(mappings.keySet());
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                String a = keys.get(i);
                String b = keys.get(j);
                Overlap overlap = overlap(a, b);
                if (overlap == Overlap.NONE || !reportedOverlaps.add(a + "\n" + b)) {
                    continue;
                }
                Site first = mappings.get(a).get(0);
                Site second = mappings.get(b).get(0);
                switch (overlap) {
                    case FIRST_LITERAL -> noteLiteral(messager, first, second);
                    case SECOND_LITERAL -> noteLiteral(messager, second, first);
                    default -> {
                        ambiguous(messager, first, second);
                        ambiguous(messager, second, first);
                    }
                }
            }
        }
    }

    private static void noteLiteral(Messager messager, Site literal, Site variable) {
        messager.printMessage(Diagnostic.Kind.NOTE, "[ai-atlas] REST mapping " + literal.route() + " of "
                + literal.declaration() + " has a literal segment where " + variable.route() + " of "
                + variable.declaration() + " has a variable. Spring routes a matching request to the literal one",
                literal.element());
    }

    private static void ambiguous(Messager messager, Site site, Site other) {
        messager.printMessage(Diagnostic.Kind.ERROR, "[ai-atlas] REST mapping " + site.route() + " of "
                + site.declaration() + " matches the same requests as " + other.route() + " of "
                + other.declaration() + ", and neither is more specific, so Spring cannot choose between them;"
                + " change one @Rest path", site.element());
    }

    /** How two distinct routes of the same HTTP method overlap. */
    private enum Overlap {
        /** No request matches both. */
        NONE,
        /** Every differing segment is a literal in the first route and a variable in the second. */
        FIRST_LITERAL,
        /** Every differing segment is a literal in the second route and a variable in the first. */
        SECOND_LITERAL,
        /** Both have a literal where the other has a variable. */
        AMBIGUOUS
    }

    private static Overlap overlap(String a, String b) {
        int spaceA = a.indexOf(' ');
        int spaceB = b.indexOf(' ');
        if (!a.substring(0, spaceA).equals(b.substring(0, spaceB))) {
            return Overlap.NONE;
        }
        String[] segmentsA = a.substring(spaceA + 1).split("/", -1);
        String[] segmentsB = b.substring(spaceB + 1).split("/", -1);
        if (segmentsA.length != segmentsB.length) {
            return Overlap.NONE;
        }
        boolean firstLiteral = false;
        boolean secondLiteral = false;
        for (int i = 0; i < segmentsA.length; i++) {
            String x = segmentsA[i];
            String y = segmentsB[i];
            if (x.equals(y)) {
                continue;
            }
            if (VARIABLE.equals(y)) {
                firstLiteral = true;
            } else if (VARIABLE.equals(x)) {
                secondLiteral = true;
            } else {
                return Overlap.NONE;
            }
        }
        if (firstLiteral && secondLiteral) {
            return Overlap.AMBIGUOUS;
        }
        return firstLiteral ? Overlap.FIRST_LITERAL : secondLiteral ? Overlap.SECOND_LITERAL : Overlap.NONE;
    }
}
