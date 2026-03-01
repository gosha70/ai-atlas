/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.atlas.annotations;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.RetentionPolicy;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationMetadataTest {

    @Test
    void agentVisible_hasFieldTarget() {
        var target = AgentVisible.class.getAnnotation(java.lang.annotation.Target.class);
        assertThat(target.value()).containsExactly(ElementType.FIELD);
    }

    @Test
    void agentVisible_hasRuntimeRetention() {
        var retention = AgentVisible.class.getAnnotation(java.lang.annotation.Retention.class);
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    void agentVisibleClass_hasTypeTarget() {
        var target = AgentVisibleClass.class.getAnnotation(java.lang.annotation.Target.class);
        assertThat(target.value()).containsExactly(ElementType.TYPE);
    }

    @Test
    void agentVisibleClass_hasRuntimeRetention() {
        var retention = AgentVisibleClass.class.getAnnotation(java.lang.annotation.Retention.class);
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    void agenticExposed_hasTypeAndMethodTargets() {
        var target = AgenticExposed.class.getAnnotation(java.lang.annotation.Target.class);
        assertThat(target.value()).containsExactlyInAnyOrder(ElementType.TYPE, ElementType.METHOD);
    }

    @Test
    void agenticExposed_hasRuntimeRetention() {
        var retention = AgenticExposed.class.getAnnotation(java.lang.annotation.Retention.class);
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    void agentVisible_defaultDescriptionIsEmpty() throws Exception {
        var method = AgentVisible.class.getDeclaredMethod("description");
        assertThat(method.getDefaultValue()).isEqualTo("");
    }

    @Test
    void agentVisible_defaultSensitiveIsFalse() throws Exception {
        var method = AgentVisible.class.getDeclaredMethod("sensitive");
        assertThat(method.getDefaultValue()).isEqualTo(false);
    }

    @Test
    void agentVisible_defaultNameIsEmpty() throws Exception {
        var method = AgentVisible.class.getDeclaredMethod("name");
        assertThat(method.getDefaultValue()).isEqualTo("");
    }

    @Test
    void agentVisible_defaultCheckCircularReferenceIsTrue() throws Exception {
        var method = AgentVisible.class.getDeclaredMethod("checkCircularReference");
        assertThat(method.getDefaultValue()).isEqualTo(true);
    }

    @Test
    void agentVisibleClass_defaultNameIsEmpty() throws Exception {
        var method = AgentVisibleClass.class.getDeclaredMethod("name");
        assertThat(method.getDefaultValue()).isEqualTo("");
    }

    @Test
    void agentVisibleClass_defaultDescriptionIsEmpty() throws Exception {
        var method = AgentVisibleClass.class.getDeclaredMethod("description");
        assertThat(method.getDefaultValue()).isEqualTo("");
    }

    @Test
    void agentVisibleClass_defaultIncludeTypeInfoIsTrue() throws Exception {
        var method = AgentVisibleClass.class.getDeclaredMethod("includeTypeInfo");
        assertThat(method.getDefaultValue()).isEqualTo(true);
    }

    @Test
    void agentVisibleClass_defaultDtoNameIsEmpty() throws Exception {
        var method = AgentVisibleClass.class.getDeclaredMethod("dtoName");
        assertThat(method.getDefaultValue()).isEqualTo("");
    }

    @Test
    void agentVisibleClass_defaultPackageNameIsEmpty() throws Exception {
        var method = AgentVisibleClass.class.getDeclaredMethod("packageName");
        assertThat(method.getDefaultValue()).isEqualTo("");
    }

    @Test
    void agenticExposed_defaultToolNameIsEmpty() throws Exception {
        var method = AgenticExposed.class.getDeclaredMethod("toolName");
        assertThat(method.getDefaultValue()).isEqualTo("");
    }

    @Test
    void agenticExposed_defaultDescriptionIsEmpty() throws Exception {
        var method = AgenticExposed.class.getDeclaredMethod("description");
        assertThat(method.getDefaultValue()).isEqualTo("");
    }

    @Test
    void agenticExposed_defaultReturnTypeIsVoid() throws Exception {
        var method = AgenticExposed.class.getDeclaredMethod("returnType");
        assertThat(method.getDefaultValue()).isEqualTo(void.class);
    }
}
