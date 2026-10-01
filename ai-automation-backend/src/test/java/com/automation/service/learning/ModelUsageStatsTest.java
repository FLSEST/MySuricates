package com.automation.service.learning;

import com.automation.dto.response.DashboardResponse.ModelStats;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ModelUsageStatsTest {

    private final ModelUsageStats stats = new ModelUsageStats();

    @Test
    void normalizeModelIdEntferntNurEinAngehaengtesDatum() {
        assertThat(ModelUsageStats.normalizeModelId("claude-sonnet-20251001")).isEqualTo("claude-sonnet");
        assertThat(ModelUsageStats.normalizeModelId("model-202510011200")).isEqualTo("model");
        assertThat(ModelUsageStats.normalizeModelId("model-2025100")).isEqualTo("model-2025100");
        assertThat(ModelUsageStats.normalizeModelId("model-20251001-beta")).isEqualTo("model-20251001-beta");
        assertThat(ModelUsageStats.normalizeModelId("gpt-5.5")).isEqualTo("gpt-5.5");
    }

    @Test
    void summiertSchritteTokensUndKostenJeSchluessel() {
        stats.record(meta("sonnet", "CLAUDE", "claude-sonnet-20260101"), false, 100, 0.25);
        stats.record(meta("sonnet", "CLAUDE", "claude-sonnet-20260201"), false, 50, 0.5);

        assertThat(stats.stats()).singleElement().satisfies(s -> {
            assertThat(s.model()).isEqualTo("claude-sonnet");
            assertThat(s.requestedModel()).isEqualTo("sonnet");
            assertThat(s.provider()).isEqualTo("CLAUDE");
            assertThat(s.stepCount()).isEqualTo(2);
            assertThat(s.totalTokens()).isEqualTo(150);
            assertThat(s.totalCostUsd()).isCloseTo(0.75, within(1e-9));
        });
    }

    @Test
    void trenntNachAnfrageAnbieterUndGemeldetemModell() {
        stats.record(meta("sonnet", "CLAUDE", "claude-sonnet"), false, 1, 0);
        stats.record(meta("sonnet", "PI", "claude-sonnet"), false, 1, 0);
        stats.record(meta("opus", "CLAUDE", "claude-sonnet"), false, 1, 0);
        stats.record(meta("sonnet", "CLAUDE", "claude-opus"), false, 1, 0);

        assertThat(stats.stats()).hasSize(4).allSatisfy(s -> assertThat(s.stepCount()).isEqualTo(1));
    }

    @Test
    void ueberspringtIntegrationsschritte() {
        stats.record(meta(null, "jira", "connector-model"), true, 500, 1.0);

        assertThat(stats.stats()).isEmpty();
    }

    @Test
    void ueberspringtSchritteOhneJedeModellangabe() {
        stats.record(Map.of("blockType", "shell"), false, 10, 0);
        stats.record(meta(" ", "", null), false, 10, 0);
        stats.record(Map.of("model", 42), false, 10, 0);

        assertThat(stats.stats()).isEmpty();
    }

    @Test
    void zaehltAuchOhneGemeldetesModellUndBehandeltLeerwerteAlsFehlend() {
        stats.record(meta("gpt-model", "CODEX", " "), false, 7, 0.1);

        assertThat(stats.stats()).containsExactly(new ModelStats(null, "gpt-model", "CODEX", 1, 7, 0.1));
    }

    @Test
    void sortiertNachTokensAbsteigend() {
        stats.record(meta("haiku", "CLAUDE", "claude-haiku"), false, 10, 0);
        stats.record(meta("opus", "CLAUDE", "claude-opus"), false, 900, 0);
        stats.record(meta("sonnet", "CLAUDE", "claude-sonnet"), false, 300, 0);

        assertThat(stats.stats()).extracting(ModelStats::requestedModel)
                .containsExactly("opus", "sonnet", "haiku");
    }

    @Test
    void ohneSchritteLeer() {
        assertThat(stats.stats()).isEmpty();
    }

    private static Map<String, Object> meta(String requestedModel, String provider, String model) {
        Map<String, Object> meta = new HashMap<>();
        if (requestedModel != null) meta.put("requestedModel", requestedModel);
        if (provider != null) meta.put("provider", provider);
        if (model != null) meta.put("model", model);
        return meta;
    }
}
