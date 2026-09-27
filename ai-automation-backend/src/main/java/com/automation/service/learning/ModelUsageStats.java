package com.automation.service.learning;

import com.automation.dto.response.DashboardResponse;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.automation.service.learning.MetadataExtractor.extractString;

/**
 * Model usage of the dashboard, per step: grouped by requested model, provider and reported model,
 * so a request and its response stay distinct — also for calls without a reported model.
 * Split out of {@link LearningService} to keep it under the source size ratchet.
 */
final class ModelUsageStats {

    private record ModelKey(String requestedModel, String provider, String model) {}

    private final Map<ModelKey, long[]> stepCount = new LinkedHashMap<>();
    private final Map<ModelKey, long[]> tokens = new LinkedHashMap<>();
    private final Map<ModelKey, double[]> cost = new LinkedHashMap<>();

    /** Strips trailing date version suffixes like {@code -20251001} from model IDs. */
    static String normalizeModelId(String model) {
        return model.replaceAll("-\\d{8,}$", "");
    }

    /** Counts one step. Integration providers describe connectors, not AI model usage. */
    void record(Map<String, Object> meta, boolean integrationStep, long stepTokens, double stepCost) {
        String model = metadata(meta, "model");
        String requestedModel = metadata(meta, "requestedModel");
        String provider = metadata(meta, "provider");
        if (integrationStep || (model == null && requestedModel == null && provider == null)) {
            return;
        }
        ModelKey key = new ModelKey(requestedModel, provider, model != null ? normalizeModelId(model) : null);
        stepCount.computeIfAbsent(key, k -> new long[1])[0]++;
        tokens.computeIfAbsent(key, k -> new long[1])[0] += stepTokens;
        cost.computeIfAbsent(key, k -> new double[1])[0] += stepCost;
    }

    /** One entry per model key, most tokens first. */
    List<DashboardResponse.ModelStats> stats() {
        return stepCount.entrySet().stream()
                .map(e -> {
                    ModelKey m = e.getKey();
                    return new DashboardResponse.ModelStats(m.model(), m.requestedModel(), m.provider(),
                            e.getValue()[0], tokens.getOrDefault(m, new long[]{0})[0],
                            cost.getOrDefault(m, new double[]{0.0})[0]);
                })
                .sorted(Comparator.comparingLong(DashboardResponse.ModelStats::totalTokens).reversed())
                .toList();
    }

    private static String metadata(Map<String, Object> metadata, String key) {
        String value = extractString(metadata, key);
        return value == null || value.isBlank() ? null : value;
    }
}
