/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.runtime.json;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Reflection-based Hibernate utilities that work whether or not Hibernate
 * is on the classpath. Avoids a hard compile-time dependency on Hibernate
 * in the runtime module.
 *
 * <p>Handles:
 * <ul>
 *   <li>{@code HibernateProxy} — unwraps lazy-loaded single associations</li>
 *   <li>{@code PersistentCollection} — detects uninitialized collections</li>
 * </ul>
 *
 * <p>Inspired by the lazy-fetching challenges described in
 * "The Hidden Cost of Lazy Fetching: Solving Hibernate's Tricky Puzzle"
 * and the Appian {@code CopilotPromptTypeAdapter} unwrapProxy pattern.
 */
public final class HibernateSupport {

    private static final Logger LOG = LoggerFactory.getLogger(HibernateSupport.class);

    private static final boolean HIBERNATE_AVAILABLE;
    private static final Class<?> HIBERNATE_PROXY_CLASS;
    private static final Class<?> PERSISTENT_COLLECTION_CLASS;
    private static final Method UNPROXY_METHOD;
    private static final Method IS_INITIALIZED_METHOD;
    private static final Method COLLECTION_WAS_INITIALIZED_METHOD;

    static {
        boolean available = false;
        Class<?> proxyClass = null;
        Class<?> collectionClass = null;
        Method unproxyMethod = null;
        Method isInitializedMethod = null;
        Method collectionWasInitialized = null;

        try {
            proxyClass = Class.forName("org.hibernate.proxy.HibernateProxy");
            collectionClass = Class.forName("org.hibernate.collection.spi.PersistentCollection");
            Class<?> hibernateClass = Class.forName("org.hibernate.Hibernate");
            unproxyMethod = hibernateClass.getMethod("unproxy", Object.class);
            isInitializedMethod = hibernateClass.getMethod("isInitialized", Object.class);
            collectionWasInitialized = collectionClass.getMethod("wasInitialized");
            available = true;
            LOG.debug("[ai-adam] Hibernate detected on classpath — proxy unwrapping enabled");
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            LOG.debug("[ai-adam] Hibernate not on classpath — proxy unwrapping disabled");
        }

        HIBERNATE_AVAILABLE = available;
        HIBERNATE_PROXY_CLASS = proxyClass;
        PERSISTENT_COLLECTION_CLASS = collectionClass;
        UNPROXY_METHOD = unproxyMethod;
        IS_INITIALIZED_METHOD = isInitializedMethod;
        COLLECTION_WAS_INITIALIZED_METHOD = collectionWasInitialized;
    }

    private HibernateSupport() {
    }

    /**
     * Returns true if Hibernate is available on the classpath.
     */
    public static boolean isHibernateAvailable() {
        return HIBERNATE_AVAILABLE;
    }

    /**
     * Checks if the given object is a Hibernate proxy.
     */
    public static boolean isProxy(Object obj) {
        if (!HIBERNATE_AVAILABLE || obj == null) {
            return false;
        }
        return HIBERNATE_PROXY_CLASS.isInstance(obj);
    }

    /**
     * Unwraps a Hibernate proxy to get the actual entity instance.
     * If the object is not a proxy or Hibernate is not available, returns the object as-is.
     *
     * <p>Equivalent to {@code Hibernate.unproxy(entity)}.
     *
     * @param entity the potentially proxied entity
     * @return the unwrapped entity, or the original object
     */
    public static Object unproxy(Object entity) {
        if (!HIBERNATE_AVAILABLE || entity == null) {
            return entity;
        }
        try {
            if (HIBERNATE_PROXY_CLASS.isInstance(entity)) {
                return UNPROXY_METHOD.invoke(null, entity);
            }
        } catch (Exception e) {
            LOG.warn("[ai-adam] Failed to unproxy entity: {}", e.getMessage());
        }
        return entity;
    }

    /**
     * Checks if a Hibernate proxy or lazy field has been initialized.
     *
     * @param obj the object to check
     * @return true if initialized (or if Hibernate is not available)
     */
    public static boolean isInitialized(Object obj) {
        if (!HIBERNATE_AVAILABLE || obj == null) {
            return true;
        }
        try {
            return (boolean) IS_INITIALIZED_METHOD.invoke(null, obj);
        } catch (Exception e) {
            LOG.warn("[ai-adam] Failed to check initialization: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Checks if the given object is a Hibernate PersistentCollection.
     */
    public static boolean isPersistentCollection(Object obj) {
        if (!HIBERNATE_AVAILABLE || obj == null) {
            return false;
        }
        return PERSISTENT_COLLECTION_CLASS.isInstance(obj);
    }

    /**
     * Checks if a PersistentCollection has been initialized.
     *
     * @param collection the collection to check
     * @return true if initialized (or if not a PersistentCollection)
     */
    public static boolean isCollectionInitialized(Object collection) {
        if (!HIBERNATE_AVAILABLE || collection == null) {
            return true;
        }
        if (!PERSISTENT_COLLECTION_CLASS.isInstance(collection)) {
            return true;
        }
        try {
            return (boolean) COLLECTION_WAS_INITIALIZED_METHOD.invoke(collection);
        } catch (Exception e) {
            LOG.warn("[ai-adam] Failed to check collection initialization: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Safely resolves a field value, handling Hibernate proxies and
     * uninitialized collections. Returns null for uninitialized lazy fields
     * instead of triggering a LazyInitializationException.
     *
     * @param value the raw field value (possibly a proxy or PersistentCollection)
     * @return the resolved value, or null if not initialized
     */
    public static Object safeResolve(Object value) {
        if (value == null) {
            return null;
        }

        // Handle PersistentCollection first
        if (isPersistentCollection(value)) {
            if (!isCollectionInitialized(value)) {
                LOG.debug("[ai-adam] Skipping uninitialized collection");
                return null;
            }
            // Collection is initialized — return as-is (it's a valid Collection)
            return value;
        }

        // Handle HibernateProxy
        if (isProxy(value)) {
            if (!isInitialized(value)) {
                LOG.debug("[ai-adam] Skipping uninitialized proxy");
                return null;
            }
            return unproxy(value);
        }

        return value;
    }
}
