package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.executor.ExecutionContext;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Injects platform data into the {@link ExecutionContext} as template variables ({@code previous_report},
 * {@code open_todos}, {@code request_metrics}, with {@code goal_id} also {@code goal_status}/{@code goal_guard_*},
 * and for an audit workflow {@code audit_findings}) so workflows never need direct DB or API access.
 * Extracted from {@code WorkflowExecutor} (pure data queries).
 *
 * <p>Every variable comes from its own {@link PlatformVariableSource} bean; this class only collects them
 * and keeps their failures apart. It used to load all of them itself and had grown to 289 lines and seven
 * collaborators, one more with every new variable (TODO 441a4c46).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowContextDataService {

    /** In {@link org.springframework.core.annotation.Order}: previous_report, open_todos, request_metrics, goal, audit. */
    private final List<PlatformVariableSource> sources;
    private final ActivityLogService activityLogService;

    /**
     * What a failed injection writes into the context instead of leaving the variable absent.
     * Same shape as the audit fact format's gap line ({@code docs/audit-workflow-protocol.md}):
     * a missing value has to be stated, not left blank and not left out.
     */
    static final String GAP_FORMAT = "NICHT GEMESSEN: %s (nicht verfügbar: %s)";

    /**
     * Injects platform data into the execution context so workflows don't need
     * direct DB or API access. Available as {{ variable_name }} in block templates.
     *
     * <p>The platform API key is intentionally <b>not</b> injected as a template
     * variable. Doing so would resolve the literal key into block prompts, then
     * into LLM output, and finally persist it in {@link com.automation.entity.StepResult#getOutput()}
     * (SQLite, backups, UI). Nor does it reach the spawned CLI or shell process as
     * {@code AUTOMATION_API_KEY}: {@link com.automation.util.ChildProcessEnv} strips it
     * (TODO 62037bf2) — a prompt with foreign content must not be able to drive the API.
     * Everything a workflow needs from the platform comes in as a context variable here.
     *
     * <p><b>No shared catch.</b> Every source carries its own error path: one that fails
     * neither costs the others nor leaves its own variables absent — it states the gap
     * ({@link #GAP_FORMAT}) and says so out loud in the activity log. A shared
     * {@code catch (Exception)} around all injections hid exactly this for months: {@code request_metrics}
     * was never set, six tests stayed green because the two variables set before it still looked
     * right, and a gate block reading the empty value passed as GREEN. TODO 62d186b8 (the case was written
     * up as „Sechs grüne Tests über einer Variable, die nie gesetzt wurde" in docs/belege.md until 2026-10).
     *
     * <p>{@code workflowMeta} decides about {@code audit_findings} (TODO 3643beac): only a workflow with
     * a valid {@code metadata.audit} gets it, through the same error path as the others.
     */
    public void injectPlatformData(ExecutionContext context, String workflowId, String projectId,
                                   Map<String, Object> workflowMeta) {
        // Untrusted: every variable carries foreign text — an earlier run's output (which may have processed
        // a webhook payload), TODO and finding titles from imports. In shell blocks they are always quoted;
        // in prompts nothing changes. See ExecutionContext#setUntrustedPlatformVariable.
        PlatformVariableSource.Request request =
                new PlatformVariableSource.Request(workflowId, projectId, workflowMeta, context::getVariable);
        for (PlatformVariableSource source : sources) {
            inject(context, workflowId, source, request);
        }
    }

    /** Short form without workflow metadata — a run that is no audit workflow. */
    public void injectPlatformData(ExecutionContext context, String workflowId, String projectId) {
        injectPlatformData(context, workflowId, projectId, Map.of());
    }

    /**
     * Every name a source can set, for any run ({@link PlatformVariableSource#allNames}). Reserved for the
     * platform (TODO 2a0695ff): {@link ExecutionContextFactory} takes none of them from an input context, and a
     * sub-workflow is not handed its parent's — every run gets its own, injected here as untrusted platform data.
     */
    public Set<String> platformVariableNames() {
        Set<String> names = new LinkedHashSet<>();
        for (PlatformVariableSource source : sources) {
            names.addAll(source.allNames());
        }
        return Collections.unmodifiableSet(names);
    }

    /**
     * Injects the variables of one source and keeps its failure to itself.
     *
     * <p>Two separate guards on purpose: a loader that throws still gets its variables set — each to
     * the stated gap, reported once under the first name — and a failure while <i>setting</i> one is at
     * least loud. Neither one reaches the next variable or source.
     *
     * <p>Only what the source announced for this run is set (TODO 2a0695ff). A value under any other name is
     * dropped, loudly: a source could otherwise set any variable as platform data — a system value such as
     * {@code project_path} included — past the reserved names that input contexts and sub-workflows are kept from.
     */
    private void inject(ExecutionContext context, String workflowId, PlatformVariableSource source,
                        PlatformVariableSource.Request request) {
        List<String> names = source.names(request);
        if (names.isEmpty()) {
            return;   // does not apply to this run: the variables do not exist
        }
        Map<String, String> values;
        try {
            values = source.load(request);
        } catch (Exception e) {
            reportGap(context, workflowId, names.getFirst(), e);
            values = new LinkedHashMap<>();
            for (String name : names) {
                values.put(name, GAP_FORMAT.formatted(name, e.getClass().getSimpleName()));
            }
        }
        for (Map.Entry<String, String> value : values.entrySet()) {
            if (!names.contains(value.getKey())) {
                reportUndeclared(context, workflowId, source, value.getKey());
                continue;
            }
            try {
                context.setUntrustedPlatformVariable(value.getKey(), value.getValue());
            } catch (Exception e) {
                // Nothing left that could still set it — but it must not fail quietly, and it must
                // not take the remaining variables with it.
                reportGap(context, workflowId, value.getKey(), e);
            }
        }
    }

    /**
     * A gap in the injected platform data is an operational fact, not a debug detail: it goes into
     * the activity log with the run id, so it is visible where the run is.
     */
    private void reportGap(ExecutionContext context, String workflowId, String name, Exception cause) {
        String runId = context != null ? context.getWorkflowRunId() : null;
        log.warn("Platform variable '{}' could not be injected (run {}): {}", name, runId, cause.toString());
        recordWarning(runId, workflowId,
                "Kontextvariable '%s' konnte nicht gesetzt werden — der Workflow sieht die Lücke statt des Werts: %s"
                        .formatted(name, cause.toString()));
    }

    /** A value the source did not announce: a bug in that source, not a detail — as loud as a gap. */
    private void reportUndeclared(ExecutionContext context, String workflowId, PlatformVariableSource source,
                                  String name) {
        String runId = context != null ? context.getWorkflowRunId() : null;
        String sourceName = source.getClass().getSimpleName().isEmpty()
                ? source.getClass().getName() : source.getClass().getSimpleName();
        log.warn("Platform source {} delivered '{}' without announcing it — dropped (run {})", sourceName, name, runId);
        recordWarning(runId, workflowId,
                "Plattform-Quelle %s lieferte die nicht angemeldete Variable '%s' — verworfen".formatted(sourceName, name));
    }

    /** Its own try/catch — a failing log write must not become the failure that eats the next injection. */
    private void recordWarning(String runId, String workflowId, String message) {
        try {
            activityLogService.warn(LogCategory.EXECUTION, message, "WorkflowContextDataService", runId, workflowId);
        } catch (Exception loggingFailure) {
            log.warn("Could not record in the activity log: {} ({})", message, loggingFailure.toString());
        }
    }
}
