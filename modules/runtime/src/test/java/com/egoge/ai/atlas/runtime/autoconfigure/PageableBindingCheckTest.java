/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.autoconfigure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.data.web.SortHandlerMethodArgumentResolver;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PageableBindingCheck}: generated {@code Pageable} endpoints publish zero-based
 * {@code page}, {@code size} and {@code sort} without a prefix, so startup fails when the
 * application's resolver would bind them otherwise.
 */
class PageableBindingCheckTest {

    /** A Contract IR with one API operation taking a Pageable and one AI-only operation taking one. */
    private static final String IR = """
            {
              "irVersion": 4,
              "operations": [
                {"id": "shop.OrderService#recent(org.springframework.data.domain.Pageable)",
                 "channels": ["AI", "API"],
                 "parameters": [{"name": "pageable", "javaType": "org.springframework.data.domain.Pageable"}]},
                {"id": "shop.AuditService#entries(org.springframework.data.domain.Pageable)",
                 "channels": ["AI"],
                 "parameters": [{"name": "pageable", "javaType": "org.springframework.data.domain.Pageable"}]},
                {"id": "shop.OrderService#find(java.lang.Long)",
                 "channels": ["API"],
                 "parameters": [{"name": "id", "javaType": "java.lang.Long"}]}
              ]
            }
            """;

    @TempDir
    Path classpath;

    @Test
    void springsDefaultResolverBindsThePublishedInputs() {
        assertThat(PageableBindingCheck.problems(new PageableHandlerMethodArgumentResolver())).isEmpty();
    }

    @Test
    void eachWayOfBindingOtherInputsIsReported() {
        assertThat(PageableBindingCheck.problems(resolver(r -> r.setPageParameterName("p"))))
                .singleElement().asString().contains("as page N");
        assertThat(PageableBindingCheck.problems(resolver(r -> r.setSizeParameterName("limit"))))
                .singleElement().asString().contains("as size N");
        assertThat(PageableBindingCheck.problems(resolver(r -> r.setOneIndexedParameters(true))))
                .singleElement().asString().contains("one-indexed");
        assertThat(PageableBindingCheck.problems(resolver(r -> r.setPrefix("q_"))))
                .anySatisfy(p -> assertThat(p).contains("as page N"))
                .anySatisfy(p -> assertThat(p).contains("as size N"));
    }

    @Test
    void aRenamedSortIsOnlyAWarningAsOperationsWithoutSortableFieldsDropSort() throws IOException {
        SortHandlerMethodArgumentResolver sort = new SortHandlerMethodArgumentResolver();
        sort.setSortParameter("orderBy");
        PageableHandlerMethodArgumentResolver resolver = new PageableHandlerMethodArgumentResolver(sort);

        assertThat(PageableBindingCheck.problems(resolver)).isEmpty();
        assertThat(PageableBindingCheck.sortProblem(resolver)).contains("descending sort on id");
        assertThat(PageableBindingCheck.sortProblem(new PageableHandlerMethodArgumentResolver())).isNull();
        runner(RenamedSort.class).withClassLoader(irLoader()).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void onlyApiOperationsTakingAPageableAreChecked() throws IOException {
        assertThat(PageableBindingCheck.pageableApiOperations(irLoader()))
                .containsExactly("shop.OrderService#recent(org.springframework.data.domain.Pageable)");
    }

    @Test
    void startupFailsWhenAGeneratedPageableEndpointWouldBeBoundOtherwise() throws IOException {
        runner(OneIndexed.class).withClassLoader(irLoader()).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining(
                    "shop.OrderService#recent(org.springframework.data.domain.Pageable)").hasMessageContaining(
                    "one-indexed").hasMessageContaining("spring.data.web.pageable.page-parameter=page");
        });
    }

    @Test
    void startupSucceedsWithTheDefaultsWithoutPageableEndpointsOrWithoutAResolver() throws IOException {
        runner(Defaults.class).withClassLoader(irLoader()).run(context -> assertThat(context).hasNotFailed());
        runner(OneIndexed.class).run(context -> assertThat(context).hasNotFailed());
        runner().withClassLoader(irLoader()).run(context -> assertThat(context).hasNotFailed());
    }

    private static ApplicationContextRunner runner(Class<?>... configurations) {
        return new ApplicationContextRunner().withUserConfiguration(PageableBindingCheck.class)
                .withUserConfiguration(configurations);
    }

    private static PageableHandlerMethodArgumentResolver resolver(
            Consumer<PageableHandlerMethodArgumentResolver> customizer) {
        PageableHandlerMethodArgumentResolver resolver = new PageableHandlerMethodArgumentResolver();
        customizer.accept(resolver);
        return resolver;
    }

    private ClassLoader irLoader() throws IOException {
        Path ir = classpath.resolve(PageableBindingCheck.IR_RESOURCE);
        Files.createDirectories(ir.getParent());
        Files.writeString(ir, IR);
        return new URLClassLoader(new URL[] {classpath.toUri().toURL()}, getClass().getClassLoader());
    }

    @Configuration(proxyBeanMethods = false)
    static class Defaults {
        @Bean
        PageableHandlerMethodArgumentResolver pageableResolver() {
            return new PageableHandlerMethodArgumentResolver();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RenamedSort {
        @Bean
        PageableHandlerMethodArgumentResolver pageableResolver() {
            SortHandlerMethodArgumentResolver sort = new SortHandlerMethodArgumentResolver();
            sort.setSortParameter("orderBy");
            return new PageableHandlerMethodArgumentResolver(sort);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OneIndexed {
        @Bean
        PageableHandlerMethodArgumentResolver pageableResolver() {
            return resolver(r -> r.setOneIndexedParameters(true));
        }
    }
}
