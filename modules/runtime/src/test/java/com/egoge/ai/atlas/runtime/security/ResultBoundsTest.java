/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.security;

import com.egoge.ai.atlas.annotations.AgenticBound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.annotation.processing.Generated;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A result over the bound its generated wrapper declares ({@link AgenticBound}) is logged as a WARN
 * and passed through unchanged, on REST through {@link DtoResponseBodyAdvice}.
 */
@ExtendWith(OutputCaptureExtension.class)
class ResultBoundsTest {

    private static final String OVER = "more than the maxResults";

    /** A generated controller in the shape the processor writes for {@code maxResults = 2}. */
    @Generated("com.egoge.ai.atlas.processor")
    static class BoundedController {

        @AgenticBound(maxResults = 2)
        public List<String> top() {
            return List.of("a", "b", "c");
        }

        public List<String> list() {
            return List.of("a", "b", "c");
        }
    }

    /** A generated envelope, as the processor nests it in a wrapper. */
    @Generated("com.egoge.ai.atlas.processor")
    record PageResult<T>(List<T> content, int number, int size, boolean hasNext, long totalElements, int totalPages) {
    }

    @Test
    void aRestResultOverItsBoundIsAWarnAndIsReturnedUnchanged(CapturedOutput output) throws Exception {
        List<String> body = new BoundedController().top();

        Object written = write(body, BoundedController.class.getMethod("top"));

        assertThat(written).isSameAs(body);
        assertThat(output.getOut()).contains("[ai-atlas] /api/v1/bounded/top returned 3 results, more than the"
                + " maxResults = 2 its service method BoundedController#top declares. The result is passed"
                + " through unchanged");
    }

    @Test
    void aResultWithinItsBoundOrWithNoBoundIsNotReported(CapturedOutput output) throws Exception {
        write(List.of("a", "b"), BoundedController.class.getMethod("top"));
        write(List.of("a", "b", "c"), BoundedController.class.getMethod("list"));

        assertThat(output.getOut()).doesNotContain(OVER);
    }

    @Test
    void elementsAreCountedInCollectionsMapsArraysAndEnvelopes() {
        assertThat(ResultBounds.count(List.of(1, 2, 3))).isEqualTo(3);
        assertThat(ResultBounds.count(Map.of("a", 1, "b", 2))).isEqualTo(2);
        assertThat(ResultBounds.count(new int[4])).isEqualTo(4);
        assertThat(ResultBounds.count(new PageResult<>(List.of("x", "y"), 0, 2, true, 9, 5))).isEqualTo(2);
        // Counting a stream would consume it
        assertThat(ResultBounds.count(java.util.stream.Stream.of(1))).isEqualTo(-1);
        assertThat(ResultBounds.count(null)).isEqualTo(-1);
    }

    private static Object write(Object body, Method method) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bounded/" + method.getName());
        return new DtoResponseBodyAdvice().beforeBodyWrite(body, new MethodParameter(method, -1),
                MediaType.APPLICATION_JSON, MappingJackson2HttpMessageConverter.class,
                new ServletServerHttpRequest(request), new ServletServerHttpResponse(new MockHttpServletResponse()));
    }
}
