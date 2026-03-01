/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.atlas.runtime.json;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for HibernateSupport when Hibernate is NOT on the classpath.
 * Verifies graceful degradation — all methods return safe defaults.
 */
class HibernateSupportTest {

    @Test
    void hibernateNotAvailableOnTestClasspath() {
        // Hibernate is not a dependency of the runtime module's test configuration
        assertThat(HibernateSupport.isHibernateAvailable()).isFalse();
    }

    @Test
    void isProxyReturnsFalseWithoutHibernate() {
        assertThat(HibernateSupport.isProxy(new Object())).isFalse();
    }

    @Test
    void isProxyReturnsFalseForNull() {
        assertThat(HibernateSupport.isProxy(null)).isFalse();
    }

    @Test
    void unproxyReturnsObjectAsIsWithoutHibernate() {
        Object obj = new Object();
        assertThat(HibernateSupport.unproxy(obj)).isSameAs(obj);
    }

    @Test
    void unproxyReturnsNullForNull() {
        assertThat(HibernateSupport.unproxy(null)).isNull();
    }

    @Test
    void isInitializedReturnsTrueWithoutHibernate() {
        assertThat(HibernateSupport.isInitialized(new Object())).isTrue();
    }

    @Test
    void isInitializedReturnsTrueForNull() {
        assertThat(HibernateSupport.isInitialized(null)).isTrue();
    }

    @Test
    void isPersistentCollectionReturnsFalseWithoutHibernate() {
        assertThat(HibernateSupport.isPersistentCollection(new Object())).isFalse();
    }

    @Test
    void isCollectionInitializedReturnsTrueWithoutHibernate() {
        assertThat(HibernateSupport.isCollectionInitialized(new Object())).isTrue();
    }

    @Test
    void safeResolveReturnsObjectAsIsWithoutHibernate() {
        Object obj = new Object();
        assertThat(HibernateSupport.safeResolve(obj)).isSameAs(obj);
    }

    @Test
    void safeResolveReturnsNullForNull() {
        assertThat(HibernateSupport.safeResolve(null)).isNull();
    }
}
