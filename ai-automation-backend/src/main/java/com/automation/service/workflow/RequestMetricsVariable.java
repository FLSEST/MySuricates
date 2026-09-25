package com.automation.service.workflow;

import com.automation.service.metrics.RequestMetricsFactSheet;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code request_metrics}: the measured request costs in the audit fact format — the only sanctioned
 * way for a shell block to see them, since it must not call the REST API (CLAUDE.md). Moved out of
 * {@link WorkflowContextDataService} (TODO 441a4c46).
 */
@Component
@Order(30)
@RequiredArgsConstructor
class RequestMetricsVariable implements PlatformVariableSource {

    static final String NAME = "request_metrics";

    private final RequestMetricsFactSheet requestMetricsFactSheet;

    @Override
    public List<String> names(Request request) {
        return List.of(NAME);
    }

    @Override
    public Map<String, String> load(Request request) {
        return Map.of(NAME, value());
    }

    /**
     * Never returns an empty string: a missing measurement has to be stated, not left blank.
     *
     * <p>The audit fact format has no room for silence — a fact sheet either carries
     * {@code FAKT request_metrics_*} lines or names the gap as {@code NICHT GEMESSEN}
     * ({@code docs/audit-workflow-protocol.md}). An empty value would reach the gate block as an
     * empty measurement and pass as GREEN, which is the failure mode the format exists to prevent.
     *
     * <p>A throwing fact sheet needs no catch here: the collector turns it into the same
     * {@code NICHT GEMESSEN} line and logs it. TODO 843be7a5, TODO 62d186b8.
     */
    String value() {
        String rendered = requestMetricsFactSheet.render();
        if (rendered == null || rendered.isBlank()) {
            return "NICHT GEMESSEN: request_metrics (Faktenblatt leer)";
        }
        return rendered;
    }
}
