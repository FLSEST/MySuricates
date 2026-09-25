package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PageSuggestionTest {

    // --- Builder defaults ---

    @Test
    void should_defaultSuggestionsToEmptyJsonArray_when_usingBuilderDefaults() {
        PageSuggestion ps = PageSuggestion.builder()
                .page("/dashboard")
                .build();

        assertThat(ps.getSuggestions()).isEqualTo("[]");
    }

    @Test
    void should_defaultGeneratedByToClaude_when_usingBuilderDefaults() {
        PageSuggestion ps = PageSuggestion.builder()
                .page("/dashboard")
                .build();

        assertThat(ps.getGeneratedBy()).isEqualTo("CLAUDE");
    }

    @Test
    void should_defaultBasedOnSessionsToZero_when_usingBuilderDefaults() {
        PageSuggestion ps = PageSuggestion.builder()
                .page("/dashboard")
                .build();

        assertThat(ps.getBasedOnSessions()).isZero();
    }

    @Test
    void should_haveNullIdAndGeneratedAt_when_usingBuilderDefaults() {
        PageSuggestion ps = PageSuggestion.builder()
                .page("/dashboard")
                .build();

        assertThat(ps.getId()).isNull();
        assertThat(ps.getGeneratedAt()).isNull();
    }

    // --- Setter / field access ---

    @Test
    void should_storePage_when_setViaSetter() {
        PageSuggestion ps = new PageSuggestion();

        ps.setPage("/workflows");

        assertThat(ps.getPage()).isEqualTo("/workflows");
    }

    @Test
    void should_storeSuggestions_when_setViaSetter() {
        PageSuggestion ps = new PageSuggestion();
        String json = "[{\"title\":\"tip\",\"description\":\"desc\"}]";

        ps.setSuggestions(json);

        assertThat(ps.getSuggestions()).isEqualTo(json);
    }

    @Test
    void should_storeGeneratedBy_when_setViaSetter() {
        PageSuggestion ps = new PageSuggestion();

        ps.setGeneratedBy("GPT-4");

        assertThat(ps.getGeneratedBy()).isEqualTo("GPT-4");
    }

    @Test
    void should_storeGeneratedAt_when_setViaSetter() {
        PageSuggestion ps = new PageSuggestion();
        Instant ts = Instant.parse("2025-06-15T10:00:00Z");

        ps.setGeneratedAt(ts);

        assertThat(ps.getGeneratedAt()).isEqualTo(ts);
    }

    @Test
    void should_storeBasedOnSessions_when_setViaSetter() {
        PageSuggestion ps = new PageSuggestion();

        ps.setBasedOnSessions(42);

        assertThat(ps.getBasedOnSessions()).isEqualTo(42);
    }

    @Test
    void should_storeId_when_setViaSetter() {
        PageSuggestion ps = new PageSuggestion();

        ps.setId("custom-id");

        assertThat(ps.getId()).isEqualTo("custom-id");
    }

    // --- AllArgsConstructor ---

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        Instant now = Instant.now();

        PageSuggestion ps = new PageSuggestion(
                "ps-id", "/settings", "en", "[{\"tip\":\"use dark mode\"}]",
                "CLAUDE", now, 5
        );

        assertThat(ps.getId()).isEqualTo("ps-id");
        assertThat(ps.getPage()).isEqualTo("/settings");
        assertThat(ps.getLocale()).isEqualTo("en");
        assertThat(ps.getSuggestions()).isEqualTo("[{\"tip\":\"use dark mode\"}]");
        assertThat(ps.getGeneratedBy()).isEqualTo("CLAUDE");
        assertThat(ps.getGeneratedAt()).isEqualTo(now);
        assertThat(ps.getBasedOnSessions()).isEqualTo(5);
    }

    // --- NoArgsConstructor ---

    @Test
    void should_haveNullOrDefaultFields_when_usingNoArgsConstructor() {
        PageSuggestion ps = new PageSuggestion();

        assertThat(ps.getId()).isNull();
        assertThat(ps.getPage()).isNull();
        // @Builder.Default fields are initialized even by @NoArgsConstructor
        assertThat(ps.getSuggestions()).isEqualTo("[]");
        assertThat(ps.getGeneratedBy()).isEqualTo("CLAUDE");
        assertThat(ps.getGeneratedAt()).isNull();
        assertThat(ps.getBasedOnSessions()).isZero();
    }

    // --- Builder with explicit values ---

    @Test
    void should_useExplicitValues_when_builderOverridesDefaults() {
        Instant ts = Instant.parse("2025-01-01T00:00:00Z");

        PageSuggestion ps = PageSuggestion.builder()
                .id("explicit-id")
                .page("/todos")
                .suggestions("[{\"action\":\"create workflow\"}]")
                .generatedBy("OPUS")
                .generatedAt(ts)
                .basedOnSessions(10)
                .build();

        assertThat(ps.getId()).isEqualTo("explicit-id");
        assertThat(ps.getPage()).isEqualTo("/todos");
        assertThat(ps.getSuggestions()).isEqualTo("[{\"action\":\"create workflow\"}]");
        assertThat(ps.getGeneratedBy()).isEqualTo("OPUS");
        assertThat(ps.getGeneratedAt()).isEqualTo(ts);
        assertThat(ps.getBasedOnSessions()).isEqualTo(10);
    }

    // --- @PrePersist ---

    @Test
    void should_generateIdAndTimestamp_when_prePersistCalledWithNulls() {
        PageSuggestion ps = PageSuggestion.builder()
                .page("/dashboard")
                .build();

        assertThat(ps.getId()).isNull();
        assertThat(ps.getGeneratedAt()).isNull();

        ps.prePersist();

        assertThat(ps.getId()).isNotNull().isNotEmpty();
        assertThat(ps.getGeneratedAt()).isNotNull();
    }

    @Test
    void should_notOverrideId_when_prePersistCalledWithExistingId() {
        PageSuggestion ps = PageSuggestion.builder()
                .id("keep-this")
                .page("/dashboard")
                .build();

        ps.prePersist();

        assertThat(ps.getId()).isEqualTo("keep-this");
    }

    @Test
    void should_notOverrideGeneratedAt_when_prePersistCalledWithExistingTimestamp() {
        Instant existing = Instant.parse("2024-06-01T12:00:00Z");
        PageSuggestion ps = PageSuggestion.builder()
                .page("/dashboard")
                .generatedAt(existing)
                .build();

        ps.prePersist();

        assertThat(ps.getGeneratedAt()).isEqualTo(existing);
    }

    @Test
    void should_generateUuidFormat_when_prePersistGeneratesId() {
        PageSuggestion ps = PageSuggestion.builder()
                .page("/dashboard")
                .build();

        ps.prePersist();

        // UUID format: 8-4-4-4-12 hex chars
        assertThat(ps.getId()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }
}
