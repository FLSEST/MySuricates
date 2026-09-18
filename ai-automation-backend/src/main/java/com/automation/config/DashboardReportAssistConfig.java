package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The editor's AI proposal for a {@code dashboard-report} block (TODO f4231dc9).
 *
 * <p>Separate from the module switch on purpose: the block itself is deterministic and free, the
 * proposal costs tokens. Whoever wants to save tokens should not have to lose the block.
 */
@Component
@ConfigurationProperties(prefix = "automation.dashboard.report-assist")
@Getter
@Setter
public class DashboardReportAssistConfig {

    /** Runtime-tunable via Settings → Execution ({@code dashboard-report-assist-enabled}). */
    private boolean enabled = true;

    /** Per-call timeout of the single tool-less AI call the proposal makes. */
    private int timeoutSeconds = 120;
}
