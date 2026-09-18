package com.automation.executor.blocks;

import com.automation.dto.response.DashboardWorkflowResultResponse.Result;
import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.dashboard.DashboardWidgetResultParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * {@code dashboard-report} (TODO f4231dc9): fills the dashboard result contract from block config
 * instead of from a {@code dashboard_widget:} marker in AI output. Deterministic, no AI call, no
 * shell, no tokens.
 *
 * <p>Config keys are the contract's own fields — {@code status} (required), {@code title},
 * {@code summary}, {@code kpis}, {@code items} — all template-capable. The values are prepared by
 * {@link DashboardReportCandidate} and then checked by the very same validation the marker path
 * uses ({@code DashboardWidgetResultParser.validateOrThrow}); the result is stored under the very
 * same run-metadata key, in the very same shape ({@code toMetadata}). The read path and the widgets
 * therefore need no change and cannot tell the two producers apart — that is the point: the
 * contract is the interface, not its author.
 *
 * <p>Failure is loud: a value outside the contract makes the step FAILED with the field name in its
 * message, and nothing is written. That is the whole reason this block exists — making an AI emit
 * the marker correctly was the hurdle, and getting it wrong was silent.
 *
 * <p>The step output carries counts only, never a contract value and never the marker: every step
 * output is read by all marker parsers at the end of a run.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DashboardReportBlockExecutor implements BlockExecutor {

    private final ModuleRegistry modules;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        if (!modules.isEnabled(ModuleCatalog.DASHBOARD_REPORT)) {
            throw new WorkflowExecutionException(
                    ModuleCatalog.DASHBOARD_REPORT.disabledMessage("Block type 'dashboard-report'"));
        }
        StepResult result = new StepResult();
        result.setStepIndex(context.nextStepIndex());
        result.setStartedAt(Instant.now());
        result.setInput("dashboard-report");
        try {
            Map<String, Object> candidate = DashboardReportCandidate.build(context, block.getConfig());
            Result report = DashboardWidgetResultParser.validateOrThrow(candidate);
            context.setRunMetadata(DashboardWidgetResultParser.METADATA_KEY,
                    DashboardWidgetResultParser.toMetadata(report));
            result.setOutput(summary(report));
            result.setStatus(StepStatus.COMPLETED);
        } catch (IllegalArgumentException e) {
            // The only exception DashboardReportCandidate/validateOrThrow ever throw; their messages
            // are built from field names and constants only (never a config key, never a candidate
            // value) — see DashboardReportCandidate's javadoc. Safe to put into step output as-is.
            String message = e.getMessage() == null || e.getMessage().isBlank()
                    ? "invalid configuration" : e.getMessage();
            log.info("dashboard-report block rejected its config: {}", message);
            result.setOutput("dashboard-report fehlgeschlagen: " + message);
            result.setStatus(StepStatus.FAILED);
        } catch (RuntimeException e) {
            // Anything unexpected: a fixed message, never e.getMessage() — an uncontrolled channel
            // into step output, which every marker parser reads again at run end.
            log.warn("dashboard-report block failed unexpectedly", e);
            result.setOutput("dashboard-report fehlgeschlagen: unerwarteter Fehler");
            result.setStatus(StepStatus.FAILED);
        }
        result.setCompletedAt(Instant.now());
        result.setDurationMs(result.getCompletedAt().toEpochMilli() - result.getStartedAt().toEpochMilli());
        return result;
    }

    /** Counts and the status only — no title, no KPI text, no item text, no marker word. */
    private static String summary(Result report) {
        StringBuilder out = new StringBuilder("✓ Dashboard-Bericht ").append(report.status().name());
        if (report.title() != null) {
            out.append(" · Titel");
        }
        out.append(" · ").append(report.kpis().size()).append(" KPIs");
        out.append(" · ").append(report.items().size()).append(" Einträge");
        if (report.summary() != null) {
            out.append(" · Zusammenfassung");
        }
        return out.toString();
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("dashboard-report");
    }
}
