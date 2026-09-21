package com.automation.entity;

import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardLayoutVersionTest {

    private static void prePersist(DashboardLayoutVersion version) throws Exception {
        Method hook = Arrays.stream(DashboardLayoutVersion.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PrePersist.class))
                .findFirst().orElseThrow();
        hook.setAccessible(true);
        hook.invoke(version);
    }

    @Test
    void maps_to_the_versions_table() {
        assertThat(DashboardLayoutVersion.class.getAnnotation(Table.class).name()).isEqualTo("dashboard_layout_versions");
    }

    @Test
    void builder_defaults_created_at_to_now() {
        Instant before = Instant.now();

        DashboardLayoutVersion version = DashboardLayoutVersion.builder().build();

        assertThat(version.getCreatedAt()).isBetween(before, Instant.now());
        assertThat(version.getId()).isNull();
        assertThat(version.getName()).isNull();
    }

    @Test
    void pre_persist_assigns_a_uuid_when_the_id_is_missing_or_blank() throws Exception {
        DashboardLayoutVersion missing = DashboardLayoutVersion.builder().build();
        DashboardLayoutVersion blank = DashboardLayoutVersion.builder().id("  ").build();

        prePersist(missing);
        prePersist(blank);

        assertThat(UUID.fromString(missing.getId())).isNotNull();
        assertThat(UUID.fromString(blank.getId())).isNotNull();
        assertThat(missing.getId()).isNotEqualTo(blank.getId());
    }

    @Test
    void pre_persist_keeps_an_existing_id() throws Exception {
        DashboardLayoutVersion version = DashboardLayoutVersion.builder().id("version-1").build();

        prePersist(version);

        assertThat(version.getId()).isEqualTo("version-1");
    }

    @Test
    void builder_and_setters_carry_every_field() {
        Instant created = Instant.parse("2026-09-15T10:00:00Z");
        DashboardLayoutVersion version = DashboardLayoutVersion.builder()
                .id("v1").createdAt(created).deviceLabel("Laptop").formatVersion(2).viewCount(3)
                .contentHash("abc").content("{}").build();
        version.setName("Release");

        assertThat(version.getCreatedAt()).isEqualTo(created);
        assertThat(version.getDeviceLabel()).isEqualTo("Laptop");
        assertThat(version.getFormatVersion()).isEqualTo(2);
        assertThat(version.getViewCount()).isEqualTo(3);
        assertThat(version.getContentHash()).isEqualTo("abc");
        assertThat(version.getContent()).isEqualTo("{}");
        assertThat(version.getName()).isEqualTo("Release");
    }
}
