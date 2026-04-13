package com.automation.entity;

import jakarta.persistence.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateVerificationTest {

    // --- Builder defaults ---

    @Test
    void should_defaultAllFieldsToNull_when_usingBuilder() {
        TemplateVerification tv = TemplateVerification.builder().build();

        assertThat(tv.getId()).isNull();
        assertThat(tv.getTemplateId()).isNull();
        assertThat(tv.getProjectId()).isNull();
        assertThat(tv.getLastRunId()).isNull();
        assertThat(tv.getVerifiedAt()).isNull();
    }

    // --- Setter / field access ---

    @Test
    void should_storeId_when_setViaSetter() {
        TemplateVerification tv = new TemplateVerification();

        tv.setId("tv-123");

        assertThat(tv.getId()).isEqualTo("tv-123");
    }

    @Test
    void should_storeTemplateId_when_setViaSetter() {
        TemplateVerification tv = new TemplateVerification();

        tv.setTemplateId("template-abc");

        assertThat(tv.getTemplateId()).isEqualTo("template-abc");
    }

    @Test
    void should_storeProjectId_when_setViaSetter() {
        TemplateVerification tv = new TemplateVerification();

        tv.setProjectId("project-xyz");

        assertThat(tv.getProjectId()).isEqualTo("project-xyz");
    }

    @Test
    void should_storeLastRunId_when_setViaSetter() {
        TemplateVerification tv = new TemplateVerification();

        tv.setLastRunId("run-456");

        assertThat(tv.getLastRunId()).isEqualTo("run-456");
    }

    @Test
    void should_storeVerifiedAt_when_setViaSetter() {
        TemplateVerification tv = new TemplateVerification();
        Instant ts = Instant.parse("2024-06-15T10:00:00Z");

        tv.setVerifiedAt(ts);

        assertThat(tv.getVerifiedAt()).isEqualTo(ts);
    }

    // --- NoArgsConstructor defaults ---

    @Test
    void should_defaultAllFieldsToNull_when_usingNoArgsConstructor() {
        TemplateVerification tv = new TemplateVerification();

        assertThat(tv.getId()).isNull();
        assertThat(tv.getTemplateId()).isNull();
        assertThat(tv.getProjectId()).isNull();
        assertThat(tv.getLastRunId()).isNull();
        assertThat(tv.getVerifiedAt()).isNull();
    }

    // --- AllArgsConstructor ---

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        Instant now = Instant.now();

        TemplateVerification tv = new TemplateVerification(
                "tv-id", "template-1", "project-1", "run-1", now
        );

        assertThat(tv.getId()).isEqualTo("tv-id");
        assertThat(tv.getTemplateId()).isEqualTo("template-1");
        assertThat(tv.getProjectId()).isEqualTo("project-1");
        assertThat(tv.getLastRunId()).isEqualTo("run-1");
        assertThat(tv.getVerifiedAt()).isEqualTo(now);
    }

    // --- Builder with values ---

    @Test
    void should_setAllFields_when_usingBuilder() {
        Instant verifiedAt = Instant.parse("2025-01-01T12:00:00Z");

        TemplateVerification tv = TemplateVerification.builder()
                .id("tv-builder")
                .templateId("tmpl-99")
                .projectId("proj-42")
                .lastRunId("run-77")
                .verifiedAt(verifiedAt)
                .build();

        assertThat(tv.getId()).isEqualTo("tv-builder");
        assertThat(tv.getTemplateId()).isEqualTo("tmpl-99");
        assertThat(tv.getProjectId()).isEqualTo("proj-42");
        assertThat(tv.getLastRunId()).isEqualTo("run-77");
        assertThat(tv.getVerifiedAt()).isEqualTo(verifiedAt);
    }

    // --- @PrePersist ensureId ---

    @Test
    void should_generateId_when_idIsNullOnPrePersist() throws Exception {
        TemplateVerification tv = TemplateVerification.builder().build();
        assertThat(tv.getId()).isNull();

        invokeEnsureId(tv);

        assertThat(tv.getId()).isNotNull().isNotBlank();
    }

    @Test
    void should_generateId_when_idIsBlankOnPrePersist() throws Exception {
        TemplateVerification tv = TemplateVerification.builder().id("  ").build();

        invokeEnsureId(tv);

        assertThat(tv.getId()).isNotBlank().doesNotContain("  ");
    }

    @Test
    void should_keepExistingId_when_idIsAlreadySetOnPrePersist() throws Exception {
        TemplateVerification tv = TemplateVerification.builder().id("existing-id").build();

        invokeEnsureId(tv);

        assertThat(tv.getId()).isEqualTo("existing-id");
    }

    @Test
    void should_generateUuidFormat_when_idIsNullOnPrePersist() throws Exception {
        TemplateVerification tv = TemplateVerification.builder().build();

        invokeEnsureId(tv);

        // UUID format: 8-4-4-4-12 hex chars
        assertThat(tv.getId()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        );
    }

    // --- JPA annotation checks ---

    @Test
    void should_beAnnotatedAsEntity() {
        assertThat(TemplateVerification.class.isAnnotationPresent(Entity.class)).isTrue();
    }

    @Test
    void should_haveCorrectTableName() {
        Table table = TemplateVerification.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("template_verifications");
    }

    @Test
    void should_haveIdAnnotationOnIdField() throws NoSuchFieldException {
        assertThat(TemplateVerification.class.getDeclaredField("id")
                .isAnnotationPresent(Id.class)).isTrue();
    }

    @Test
    void should_haveColumnAnnotationsOnFields() throws NoSuchFieldException {
        assertThat(TemplateVerification.class.getDeclaredField("templateId")
                .getAnnotation(Column.class).name()).isEqualTo("template_id");
        assertThat(TemplateVerification.class.getDeclaredField("projectId")
                .getAnnotation(Column.class).name()).isEqualTo("project_id");
        assertThat(TemplateVerification.class.getDeclaredField("lastRunId")
                .getAnnotation(Column.class).name()).isEqualTo("last_run_id");
        assertThat(TemplateVerification.class.getDeclaredField("verifiedAt")
                .getAnnotation(Column.class).name()).isEqualTo("verified_at");
    }

    @Test
    void should_markRequiredColumnsAsNotNullable() throws NoSuchFieldException {
        assertThat(TemplateVerification.class.getDeclaredField("templateId")
                .getAnnotation(Column.class).nullable()).isFalse();
        assertThat(TemplateVerification.class.getDeclaredField("projectId")
                .getAnnotation(Column.class).nullable()).isFalse();
        assertThat(TemplateVerification.class.getDeclaredField("verifiedAt")
                .getAnnotation(Column.class).nullable()).isFalse();
    }

    @Test
    void should_allowNullOnOptionalColumns() throws NoSuchFieldException {
        // lastRunId has no nullable=false, so defaults to true
        Column lastRunIdCol = TemplateVerification.class.getDeclaredField("lastRunId")
                .getAnnotation(Column.class);
        assertThat(lastRunIdCol.nullable()).isTrue();
    }

    // --- Edge cases ---

    @Test
    void should_allowNullLastRunId_when_usingBuilder() {
        TemplateVerification tv = TemplateVerification.builder()
                .templateId("tmpl-1")
                .projectId("proj-1")
                .verifiedAt(Instant.now())
                .build();

        assertThat(tv.getLastRunId()).isNull();
    }

    @Test
    void should_allowEmptyStringFields_when_setViaSetter() {
        TemplateVerification tv = new TemplateVerification();

        tv.setTemplateId("");
        tv.setProjectId("");
        tv.setLastRunId("");

        assertThat(tv.getTemplateId()).isEmpty();
        assertThat(tv.getProjectId()).isEmpty();
        assertThat(tv.getLastRunId()).isEmpty();
    }

    // --- Helper ---

    private void invokeEnsureId(TemplateVerification tv) throws Exception {
        Method ensureId = TemplateVerification.class.getDeclaredMethod("ensureId");
        ensureId.setAccessible(true);
        ensureId.invoke(tv);
    }
}
