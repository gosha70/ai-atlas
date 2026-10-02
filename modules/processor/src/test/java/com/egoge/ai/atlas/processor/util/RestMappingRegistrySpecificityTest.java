/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.util;

import com.egoge.ai.atlas.processor.rest.RestOperation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RestMappingRegistry#SPECIFICITY} ranks two routes a request both match as Spring MVC's
 * {@link PathPattern#SPECIFICITY_COMPARATOR} does, so a compile error is reported exactly when Spring
 * would fail the request.
 */
class RestMappingRegistrySpecificityTest {

    @ParameterizedTest
    @CsvSource({
        "/orders/{id}/items, /orders/active/{region}",
        "/orders/{id}/items, /orders/open/{kind}",
        "/orders/{a}/items, /orders/items/{b}",
        "/orders/active, /orders/{id}",
        "/{a}/{b}/x, /y/{c}/{d}",
        "/{a}/x/{b}, /y/{c}/{d}",
        "/orders/{id}, /orders/{orderId}"
    })
    void ranksTwoRoutesAsSpringDoes(String first, String second) {
        PathPatternParser parser = new PathPatternParser();
        int spring = PathPattern.SPECIFICITY_COMPARATOR.compare(parser.parse(first), parser.parse(second));

        int ours = RestMappingRegistry.SPECIFICITY.compare(RestOperation.routeKey("GET", first),
                RestOperation.routeKey("GET", second));

        assertThat(Integer.signum(ours)).isEqualTo(Integer.signum(spring));
    }
}
