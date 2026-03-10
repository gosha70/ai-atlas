/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

class VersionRangeValidationTest {

    private static final JavaFileObject ENTITY = JavaFileObjects.forSourceString("test.Order",
            """
            package test;
            import com.egoge.ai.atlas.annotations.AgenticField;
            import com.egoge.ai.atlas.annotations.AgenticEntity;
            @AgenticEntity
            public class Order {
                @AgenticField(description = "ID") private Long id;
                public Long getId() { return id; }
            }
            """);

    @Test
    void apiSinceGreaterThanApiUntil_emitsError() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Find", returnType = Order.class,
                                    apiSince = 3, apiUntil = 2)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, service);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("apiSince");
        assertThat(compilation).hadErrorContaining("apiUntil");
    }

    @Test
    void apiDeprecatedSinceGreaterThanApiUntil_emitsError() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Find", returnType = Order.class,
                                    apiSince = 1, apiUntil = 2, apiDeprecatedSince = 3)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, service);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("apiDeprecatedSince");
        assertThat(compilation).hadErrorContaining("apiUntil");
    }

    @Test
    void apiSinceZero_emitsError() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Find", returnType = Order.class, apiSince = 0)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, service);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("apiSince");
    }

    @Test
    void apiSinceNegative_emitsError() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Find", returnType = Order.class, apiSince = -2)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, service);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("apiSince");
    }

    @Test
    void apiDeprecatedSinceNegative_emitsError() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Find", returnType = Order.class, apiDeprecatedSince = -2)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, service);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("apiDeprecatedSince");
    }

    @Test
    void apiDeprecatedBeforeIntroduction_emitsError() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Find", returnType = Order.class,
                                    apiSince = 3, apiDeprecatedSince = 2)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, service);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("apiDeprecatedSince");
        assertThat(compilation).hadErrorContaining("apiSince");
    }

    @Test
    void apiSinceEqualsApiUntil_valid() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Find", returnType = Order.class,
                                    apiSince = 2, apiUntil = 2)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
    }

    @Test
    void apiDeprecatedSinceEqualsApiSince_valid() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Find", returnType = Order.class,
                                    apiSince = 2, apiDeprecatedSince = 2)
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.api.major=2")
                .compile(ENTITY, service);

        assertThat(compilation).succeeded();
    }

    @Test
    void channelInheritMixedWithExplicit_emitsError() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class OrderService {
                    @AgenticExposed(description = "Find", returnType = Order.class,
                                    channels = { AgenticExposed.Channel.INHERIT, AgenticExposed.Channel.AI })
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, service);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("must not mix INHERIT");
    }

    @Test
    void classLevelChannelInheritMixedWithExplicit_emitsError() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class,
                                channels = { AgenticExposed.Channel.INHERIT, AgenticExposed.Channel.API })
                public class OrderService {
                    @AgenticExposed(description = "Find")
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, service);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("must not mix INHERIT");
    }

    @Test
    void methodWidensClassChannels_emitsError() {
        JavaFileObject service = JavaFileObjects.forSourceString("test.OrderService",
                """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Order ops", returnType = Order.class,
                                channels = { AgenticExposed.Channel.API })
                public class OrderService {
                    @AgenticExposed(description = "Find", returnType = Order.class,
                                    channels = { AgenticExposed.Channel.AI })
                    public Order findById(Long id) { return null; }
                }
                """);

        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor())
                .compile(ENTITY, service);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("must be a subset");
    }
}
