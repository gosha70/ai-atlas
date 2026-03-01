/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.runtime.json;

import ai.adam.annotations.AgentVisible;
import ai.adam.annotations.AgentVisibleClass;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Jackson serializer that safely converts {@code @AgentVisibleClass}-annotated
 * JPA entities to JSON, handling Hibernate proxies, PersistentCollections,
 * circular references, and enriched metadata output.
 *
 * <p>Inspired by Appian's {@code CopilotPromptTypeAdapter}, this serializer:
 * <ul>
 *   <li>Unwraps Hibernate proxies via {@link HibernateSupport}</li>
 *   <li>Detects circular references via {@link SerializationContext}</li>
 *   <li>Only serializes {@code @AgentVisible} fields (whitelist model)</li>
 *   <li>In enriched mode, outputs {@code {value, description, validValues}} per field</li>
 *   <li>Skips uninitialized lazy fields instead of throwing LazyInitializationException</li>
 * </ul>
 *
 * <p><strong>Enriched mode</strong> (for MCP/LLM consumption):
 * <pre>{@code
 * {
 *   "typeInfo": {"name": "Order", "description": "A customer order"},
 *   "status": {"value": "SHIPPED", "description": "...", "validValues": ["PENDING", "SHIPPED", ...]}
 * }
 * }</pre>
 *
 * <p><strong>Flat mode</strong> (for REST endpoints):
 * <pre>{@code
 * {"id": 123, "status": "SHIPPED"}
 * }</pre>
 */
public class AgentSafeSerializer extends JsonSerializer<Object> {

    private static final Logger LOG = LoggerFactory.getLogger(AgentSafeSerializer.class);

    private static final String METHOD_GET_PREFIX = "get";
    private static final String METHOD_IS_PREFIX = "is";

    /** Tracks nesting depth to clear SerializationContext after top-level serialization. */
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private final boolean enriched;
    private final boolean includeDescriptions;
    private final boolean includeValidValues;

    public AgentSafeSerializer(boolean enriched, boolean includeDescriptions, boolean includeValidValues) {
        this.enriched = enriched;
        this.includeDescriptions = includeDescriptions;
        this.includeValidValues = includeValidValues;
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }

        // Unwrap Hibernate proxies
        value = HibernateSupport.safeResolve(value);
        if (value == null) {
            gen.writeNull();
            return;
        }

        // Circular reference check
        if (SerializationContext.containsInstance(value)) {
            LOG.warn("[ai-adam] Circular reference detected for instance of {}", value.getClass().getSimpleName());
            gen.writeNull();
            return;
        }

        DEPTH.set(DEPTH.get() + 1);
        try {
            SerializationContext.addInstance(value);
            writeObject(value, gen, serializers);
        } finally {
            SerializationContext.removeInstance(value);
            int depth = DEPTH.get() - 1;
            DEPTH.set(depth);
            if (depth == 0) {
                SerializationContext.clear();
                DEPTH.remove();
            }
        }
    }

    private void writeObject(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        Class<?> clazz = value.getClass();
        AgentVisibleClass classAnnotation = clazz.getAnnotation(AgentVisibleClass.class);

        gen.writeStartObject();

        // Write typeInfo block in enriched mode
        if (enriched && classAnnotation != null && classAnnotation.includeTypeInfo()) {
            writeTypeInfo(gen, clazz, classAnnotation);
        }

        // Collect @AgentVisible getters sorted by field name for deterministic output
        Method[] methods = Arrays.stream(clazz.getMethods())
                .filter(this::isAgentVisibleGetter)
                .sorted(Comparator.comparing(m -> {
                    String name = extractFieldName(m);
                    return name != null ? name : m.getName();
                }))
                .toArray(Method[]::new);

        for (Method method : methods) {
            // @AgentVisible targets FIELD only — resolve from the corresponding field
            AgentVisible fieldAnnotation = findFieldAnnotation(clazz, method);
            if (fieldAnnotation == null) {
                continue;
            }

            String fieldName = resolveFieldName(method, fieldAnnotation);

            Object fieldValue;
            try {
                fieldValue = method.invoke(value);
            } catch (Exception e) {
                LOG.warn("[ai-adam] Failed to access field '{}': {}", fieldName, e.getMessage());
                continue;
            }

            // Safely resolve Hibernate proxies/collections
            fieldValue = HibernateSupport.safeResolve(fieldValue);

            // Check circular reference for this field
            if (fieldAnnotation.checkCircularReference() && fieldValue != null) {
                if (SerializationContext.containsInstance(fieldValue)) {
                    LOG.debug("[ai-adam] Skipping circular reference for field '{}'", fieldName);
                    continue;
                }
            }

            if (enriched) {
                writeEnrichedField(gen, serializers, fieldName, fieldValue, fieldAnnotation, method);
            } else {
                writeFlatField(gen, serializers, fieldName, fieldValue);
            }
        }

        gen.writeEndObject();
    }

    private void writeTypeInfo(JsonGenerator gen, Class<?> clazz, AgentVisibleClass annotation) throws IOException {
        gen.writeObjectFieldStart("typeInfo");
        String name = annotation.name().isEmpty() ? clazz.getSimpleName() : annotation.name();
        gen.writeStringField("name", name);
        if (!annotation.description().isEmpty()) {
            gen.writeStringField("description", annotation.description());
        }
        gen.writeEndObject();
    }

    private void writeEnrichedField(JsonGenerator gen, SerializerProvider serializers,
                                     String fieldName, Object fieldValue,
                                     AgentVisible annotation, Method method) throws IOException {
        gen.writeObjectFieldStart(fieldName);

        // Write value
        gen.writeFieldName("value");
        if (fieldValue == null) {
            gen.writeNull();
        } else if (fieldValue instanceof Collection<?> coll) {
            writeCollection(gen, serializers, coll);
        } else if (isAgentVisibleType(fieldValue.getClass())) {
            serialize(fieldValue, gen, serializers);
        } else {
            serializers.defaultSerializeValue(fieldValue, gen);
        }

        // Write description
        if (includeDescriptions && !annotation.description().isEmpty()) {
            gen.writeStringField("description", annotation.description());
        }

        // Write validValues for enum types
        if (includeValidValues) {
            Class<?> returnType = method.getReturnType();
            if (returnType.isEnum()) {
                gen.writeArrayFieldStart("validValues");
                for (Object enumConstant : returnType.getEnumConstants()) {
                    gen.writeString(((Enum<?>) enumConstant).name());
                }
                gen.writeEndArray();
            }
        }

        gen.writeEndObject();
    }

    private void writeFlatField(JsonGenerator gen, SerializerProvider serializers,
                                 String fieldName, Object fieldValue) throws IOException {
        gen.writeFieldName(fieldName);
        if (fieldValue == null) {
            gen.writeNull();
        } else if (fieldValue instanceof Collection<?> coll) {
            writeCollection(gen, serializers, coll);
        } else if (isAgentVisibleType(fieldValue.getClass())) {
            serialize(fieldValue, gen, serializers);
        } else {
            serializers.defaultSerializeValue(fieldValue, gen);
        }
    }

    private void writeCollection(JsonGenerator gen, SerializerProvider serializers,
                                  Collection<?> collection) throws IOException {
        gen.writeStartArray();
        for (Object item : collection) {
            Object resolved = HibernateSupport.safeResolve(item);
            if (resolved == null) {
                gen.writeNull();
            } else if (SerializationContext.containsInstance(resolved)) {
                LOG.debug("[ai-adam] Skipping circular reference in collection");
                continue;
            } else if (isAgentVisibleType(resolved.getClass())) {
                serialize(resolved, gen, serializers);
            } else {
                serializers.defaultSerializeValue(resolved, gen);
            }
        }
        gen.writeEndArray();
    }

    /**
     * Checks if a method is a getter whose corresponding field is
     * annotated with {@code @AgentVisible}.
     */
    private boolean isAgentVisibleGetter(Method method) {
        if (method.getParameterCount() != 0) {
            return false;
        }
        String name = method.getName();
        if (!name.startsWith(METHOD_GET_PREFIX) && !name.startsWith(METHOD_IS_PREFIX)) {
            return false;
        }
        if (name.equals("getClass")) {
            return false;
        }
        return findFieldAnnotation(method.getDeclaringClass(), method) != null;
    }

    /**
     * Finds the @AgentVisible annotation on the field corresponding to a getter method.
     */
    private AgentVisible findFieldAnnotation(Class<?> clazz, Method getter) {
        String fieldName = extractFieldName(getter);
        if (fieldName == null) {
            return null;
        }

        // Walk the class hierarchy
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                var field = current.getDeclaredField(fieldName);
                return field.getAnnotation(AgentVisible.class);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private String resolveFieldName(Method method, AgentVisible annotation) {
        if (!annotation.name().isEmpty()) {
            return annotation.name();
        }
        return extractFieldName(method);
    }

    private static String extractFieldName(Method method) {
        String methodName = method.getName();
        if (methodName.startsWith(METHOD_GET_PREFIX) && methodName.length() > 3) {
            return Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
        } else if (methodName.startsWith(METHOD_IS_PREFIX) && methodName.length() > 2) {
            return Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
        }
        return null;
    }

    private static boolean isAgentVisibleType(Class<?> clazz) {
        return clazz.isAnnotationPresent(AgentVisibleClass.class);
    }

    @Override
    public Class<Object> handledType() {
        return Object.class;
    }
}
