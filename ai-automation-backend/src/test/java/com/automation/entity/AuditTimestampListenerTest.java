package com.automation.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditTimestampListenerTest {

    private static final Instant PRESET = Instant.parse("2020-01-01T00:00:00Z");

    private final AuditTimestampListener listener = new AuditTimestampListener();

    static class Audited {
        @CreatedDate
        Instant createdAt;
        @LastModifiedDate
        Instant updatedAt;
        String title;
    }

    static class CreatedOnly {
        @CreatedDate
        Instant createdAt;
    }

    static class Subclass extends Audited {
    }

    static class NotAudited {
        Instant createdAt;
    }

    static class WrongType {
        @CreatedDate
        java.time.LocalDateTime createdAt;
    }

    @Test
    void persist_setsBothFieldsToTheSameInstant_overwritingPresetValues() {
        Audited entity = new Audited();
        entity.createdAt = PRESET;
        entity.updatedAt = PRESET;
        Instant before = Instant.now();

        listener.onPersist(entity);

        assertThat(entity.createdAt).isAfterOrEqualTo(before).isEqualTo(entity.updatedAt);
    }

    @Test
    void persist_keepsSubSecondPrecision() {
        // Hibernate's @CreationTimestamp lost this in SQLite; ordering by createdAt depends on it
        List<Instant> seen = new ArrayList<>();
        for (int i = 0; i < 50 && seen.stream().allMatch(t -> t.getNano() == 0); i++) {
            Audited entity = new Audited();
            listener.onPersist(entity);
            seen.add(entity.createdAt);
        }

        assertThat(seen).anyMatch(t -> t.getNano() != 0);
    }

    @Test
    void update_refreshesOnlyTheLastModifiedField() {
        Audited entity = new Audited();
        entity.createdAt = PRESET;
        entity.updatedAt = PRESET;

        listener.onUpdate(entity);

        assertThat(entity.createdAt).isEqualTo(PRESET);
        assertThat(entity.updatedAt).isAfter(PRESET);
    }

    @Test
    void entityWithCreatedDateOnly_isFilled() {
        CreatedOnly entity = new CreatedOnly();

        listener.onPersist(entity);
        listener.onUpdate(entity);

        assertThat(entity.createdAt).isNotNull();
    }

    @Test
    void fieldsOfSuperclasses_areFilled() {
        Subclass entity = new Subclass();

        listener.onPersist(entity);

        assertThat(entity.createdAt).isNotNull();
        assertThat(entity.updatedAt).isNotNull();
    }

    @Test
    void entityWithoutAuditFields_isLeftAlone() {
        NotAudited entity = new NotAudited();

        listener.onPersist(entity);
        listener.onUpdate(entity);

        assertThat(entity.createdAt).isNull();
    }

    @Test
    void nonInstantAuditField_isRejected() {
        assertThatThrownBy(() -> listener.onPersist(new WrongType()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WrongType.createdAt")
                .hasMessageContaining("Instant");
    }

    // ---- every entity that registers the listener ----

    static List<Class<?>> auditedEntities() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        List<Class<?>> result = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.automation")) {
            Class<?> type = ClassUtils.resolveClassName(candidate.getBeanClassName(), AuditTimestampListenerTest.class.getClassLoader());
            EntityListeners listeners = type.getAnnotation(EntityListeners.class);
            if (listeners != null && Arrays.asList(listeners.value()).contains(AuditTimestampListener.class)) {
                result.add(type);
            }
        }
        assertThat(result).hasSizeGreaterThanOrEqualTo(21);
        return result;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("auditedEntities")
    void listenerFillsTheTimestampsOf(Class<?> entityType) throws Exception {
        Constructor<?> constructor = entityType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object entity = constructor.newInstance();

        listener.onPersist(entity);

        Instant created = (Instant) read(entity, CreatedDate.class);
        assertThat(created).as(entityType.getSimpleName() + " @CreatedDate").isNotNull();
        Object modified = read(entity, LastModifiedDate.class);
        if (hasField(entityType, LastModifiedDate.class)) {
            assertThat(modified).as(entityType.getSimpleName() + " @LastModifiedDate").isEqualTo(created);
        }
    }

    private static boolean hasField(Class<?> type, Class<? extends java.lang.annotation.Annotation> annotation) {
        return Arrays.stream(type.getDeclaredFields()).anyMatch(f -> f.isAnnotationPresent(annotation));
    }

    private static Object read(Object entity, Class<? extends java.lang.annotation.Annotation> annotation) throws IllegalAccessException {
        for (Field field : entity.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(annotation)) {
                field.setAccessible(true);
                return field.get(entity);
            }
        }
        return null;
    }
}
