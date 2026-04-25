package com.automation.service.assistant;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PendingToolTest {

    @Test
    void should_expose_all_fields_when_using_canonical_constructor() {
        Instant expiresAt = Instant.parse("2030-01-01T00:00:00Z");
        PendingTool pending = new PendingTool(
                "tool-123",
                "search",
                "Search the web for cats",
                "{\"query\":\"cats\"}",
                expiresAt
        );

        assertThat(pending.id()).isEqualTo("tool-123");
        assertThat(pending.type()).isEqualTo("search");
        assertThat(pending.description()).isEqualTo("Search the web for cats");
        assertThat(pending.payloadJson()).isEqualTo("{\"query\":\"cats\"}");
        assertThat(pending.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void should_allow_null_values_in_record_fields() {
        PendingTool pending = new PendingTool(null, null, null, null, null);

        assertThat(pending.id()).isNull();
        assertThat(pending.type()).isNull();
        assertThat(pending.description()).isNull();
        assertThat(pending.payloadJson()).isNull();
        assertThat(pending.expiresAt()).isNull();
    }

    @Test
    void should_not_be_expired_when_expiresAt_is_null() {
        PendingTool pending = new PendingTool("id", "type", "desc", "{}", null);

        assertThat(pending.isExpired()).isFalse();
    }

    @Test
    void should_not_be_expired_when_expiresAt_is_in_the_future() {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        PendingTool pending = new PendingTool("id", "type", "desc", "{}", future);

        assertThat(pending.isExpired()).isFalse();
    }

    @Test
    void should_be_expired_when_expiresAt_is_in_the_past() {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        PendingTool pending = new PendingTool("id", "type", "desc", "{}", past);

        assertThat(pending.isExpired()).isTrue();
    }

    @Test
    void should_be_expired_when_expiresAt_is_far_in_the_past() {
        Instant longAgo = Instant.parse("2000-01-01T00:00:00Z");
        PendingTool pending = new PendingTool("id", "type", "desc", "{}", longAgo);

        assertThat(pending.isExpired()).isTrue();
    }

    @Test
    void should_be_equal_when_records_have_same_values() {
        Instant expiresAt = Instant.parse("2030-01-01T00:00:00Z");
        PendingTool a = new PendingTool("id", "type", "desc", "{}", expiresAt);
        PendingTool b = new PendingTool("id", "type", "desc", "{}", expiresAt);

        assertThat(a).isEqualTo(b);
    }

    @Test
    void should_have_same_hashCode_when_records_are_equal() {
        Instant expiresAt = Instant.parse("2030-01-01T00:00:00Z");
        PendingTool a = new PendingTool("id", "type", "desc", "{}", expiresAt);
        PendingTool b = new PendingTool("id", "type", "desc", "{}", expiresAt);

        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_differ_when_field_values_differ() {
        Instant expiresAt = Instant.parse("2030-01-01T00:00:00Z");
        PendingTool a = new PendingTool("id-1", "type", "desc", "{}", expiresAt);
        PendingTool b = new PendingTool("id-2", "type", "desc", "{}", expiresAt);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_render_record_in_toString() {
        PendingTool pending = new PendingTool(
                "tool-123",
                "search",
                "Search description",
                "{\"q\":\"x\"}",
                Instant.parse("2030-01-01T00:00:00Z")
        );

        String rendered = pending.toString();

        assertThat(rendered).contains("tool-123");
        assertThat(rendered).contains("search");
        assertThat(rendered).contains("Search description");
    }
}
