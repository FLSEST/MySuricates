package com.automation.entity;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fills {@link CreatedDate} and {@link LastModifiedDate} fields — the part of Spring Data JPA
 * auditing MySuricates uses, without Spring Data's {@code AuditingEntityListener}.
 *
 * <p>That listener needs {@code @EnableJpaAuditing}, which requires spring-aspects and with it
 * aspectjweaver (EPL-2.0 without a GPL secondary license) — incompatible with the AGPL
 * (TODO dc3d6101). Hibernate's {@code @CreationTimestamp}/{@code @UpdateTimestamp} are no
 * replacement: they fill the fields only at flush, and the SQLite dialect truncates them to whole
 * seconds, which breaks "newest first" for rows created in the same second.
 *
 * <p>Same behaviour as before: on persist (and on merge of a new entity) both fields get the same
 * instant, overwriting what the caller set; on an update (dirty entity at flush) the
 * last-modified field is refreshed. Only {@link Instant} fields are supported.
 */
public class AuditTimestampListener {

    private static final ClassValue<AuditFields> FIELDS = new ClassValue<>() {
        @Override
        protected AuditFields computeValue(Class<?> type) {
            return AuditFields.of(type);
        }
    };

    @PrePersist
    void onPersist(Object entity) {
        AuditFields fields = FIELDS.get(entity.getClass());
        Instant now = Instant.now();
        fields.set(fields.created(), entity, now);
        fields.set(fields.modified(), entity, now);
    }

    @PreUpdate
    void onUpdate(Object entity) {
        AuditFields fields = FIELDS.get(entity.getClass());
        fields.set(fields.modified(), entity, Instant.now());
    }

    private record AuditFields(List<Field> created, List<Field> modified) {

        static AuditFields of(Class<?> type) {
            List<Field> created = new ArrayList<>();
            List<Field> modified = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (field.isAnnotationPresent(CreatedDate.class)) {
                        created.add(accessible(field));
                    }
                    if (field.isAnnotationPresent(LastModifiedDate.class)) {
                        modified.add(accessible(field));
                    }
                }
            }
            return new AuditFields(List.copyOf(created), List.copyOf(modified));
        }

        private static Field accessible(Field field) {
            if (field.getType() != Instant.class) {
                throw new IllegalStateException("Audit timestamp " + field.getDeclaringClass().getSimpleName()
                        + "." + field.getName() + " must be an Instant, is " + field.getType().getSimpleName());
            }
            field.setAccessible(true);
            return field;
        }

        void set(List<Field> targets, Object entity, Instant value) {
            for (Field field : targets) {
                try {
                    field.set(entity, value);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot set " + field.getName(), e);
                }
            }
        }
    }
}
