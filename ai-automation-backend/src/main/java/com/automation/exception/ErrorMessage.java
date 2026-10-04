package com.automation.exception;

import lombok.Getter;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Error messages the UI shows in its own language (TODO db79450d). Where one {@link ErrorCode}
 * stands for many different messages (INVALID_ARGUMENT, STATE_CONFLICT, …), the key tells them
 * apart: the API sends it as {@code messageKey} with the params, the frontend translates it from
 * {@code errors.messages} in de.ts/en.ts.
 *
 * <p>The template is the English text with {@code {placeholders}}; en.ts holds the same text
 * (checked by {@code api/__tests__/errorText.test.ts}). Keep each template one string literal.
 */
@Getter
public enum ErrorMessage {
    MISSING_PARAMETER(ErrorCode.INVALID_ARGUMENT, "Missing required parameter: {name}"),
    INVALID_PARAMETER_VALUE(ErrorCode.INVALID_ARGUMENT, "Invalid value for parameter '{name}'"),
    MALFORMED_BODY(ErrorCode.INVALID_ARGUMENT, "Request body is missing or malformed"),
    NAMED_NOT_FOUND(ErrorCode.RESOURCE_NOT_FOUND, "{resource} not found: {id}"),
    INVALID_CRON(ErrorCode.INVALID_ARGUMENT, "Invalid cron expression: {expression}"),
    CRON_REQUIRED(ErrorCode.INVALID_ARGUMENT, "cronExpression is required"),
    // A schedule without a run left (ScheduleEndConditions, TODO 1e801952)
    SCHEDULE_MAX_RUNS_REACHED(ErrorCode.INVALID_ARGUMENT, "The schedule has already started {runCount} time(s), its maximum is {maxRuns} — raise the maximum runs, or set them to 0 for no limit"),
    SCHEDULE_NEXT_RUN_AFTER_END_DATE(ErrorCode.INVALID_ARGUMENT, "The schedule's next run ({nextRun}) is not before its end date ({endDate}) — it would never start"),
    SCHEDULE_NO_NEXT_RUN(ErrorCode.INVALID_ARGUMENT, "The cron expression {expression} has no future occurrence — the schedule would never start"),
    SCHEDULE_ONCE_IN_PAST(ErrorCode.INVALID_ARGUMENT, "The run time {scheduledAt} lies in the past — choose a time in the future"),
    UNKNOWN_SETTING(ErrorCode.INVALID_ARGUMENT, "Unknown setting: {key}"),
    UNKNOWN_EXECUTION_SETTING(ErrorCode.INVALID_ARGUMENT, "Unknown execution setting: {key}"),
    RESTRICTED_PATH(ErrorCode.INVALID_ARGUMENT, "Path points to a restricted system directory: {path}"),
    PROFILE_NEEDS_LANGUAGE(ErrorCode.INVALID_ARGUMENT, "Profile content needs at least one language"),
    MEMORY_NOT_IN_WORKFLOW(ErrorCode.INVALID_ARGUMENT, "Memory does not belong to workflow"),
    OAUTH_STATE_INVALID(ErrorCode.INVALID_ARGUMENT, "Invalid or expired OAuth state"),
    INVALID_DATE(ErrorCode.INVALID_ARGUMENT, "Invalid date: {value}"),
    NO_FAILED_RUNS(ErrorCode.INVALID_ARGUMENT, "Workflow '{name}' has no failed runs"),
    TEST_INSTANCE_NOT_RUNNING(ErrorCode.STATE_CONFLICT, "Test instance is not running or not healthy. Start it first."),
    STABLE_INSTANCE_NOT_RUNNING(ErrorCode.STATE_CONFLICT, "Stable instance is not running or not healthy. Start it first."),
    CHAIN_RUN_ACTIVE(ErrorCode.STATE_CONFLICT, "The chain still has a running or paused run. Wait for it to finish or cancel it, then delete the chain."),
    MODULE_DISABLED(ErrorCode.MODULE_DISABLED, "{what} requires module '{module}', which is disabled on this instance — set {property}=true and restart"),
    MODULE_DISABLED_SWITCHABLE(ErrorCode.MODULE_DISABLED, "{what} requires module '{module}', which is disabled on this instance — set {property}=true and restart (or switch it on under Settings → Modules if automation.modules.ui-editable is true)"),
    RUN_RETENTION_LOCKED(ErrorCode.RUN_DELETION_DISABLED, "Run retention can only be extended while deleting runs is disabled (automation.executions.allow-delete=false): {current} -> {requested} days"),
    // A sub-workflow still naming a blueprint instead of a workflow (TemplatePackageInstaller, TODO 093d2f11)
    UNWIRED_BLUEPRINT_REFERENCE(ErrorCode.INVALID_ARGUMENT, "Block '{block}' calls the blueprint {blueprint} instead of a workflow — create the package through the template installation (POST /api/v1/workflow-templates/<id>/install), which also creates the called workflow"),
    MEMORY_NO_RUNS(ErrorCode.STATE_CONFLICT, "No runs found for workflow: {id}"),
    MEMORY_NO_STEPS(ErrorCode.STATE_CONFLICT, "No step results found in selected runs"),
    INTEGRATION_PARAMETER_MISSING(ErrorCode.INVALID_ARGUMENT, "Required parameter missing: {name}"),
    // Why RunAdmission refused a sealed workflow (WorkflowSealVerifier.Problem); "{name}" is the workflow
    SEAL_DEFINITION_CHANGED(ErrorCode.WORKFLOW_TAMPERED, "Sealed workflow '{name}' was not started: its definition no longer matches the seal"),
    SEAL_SKILLS_CHANGED(ErrorCode.WORKFLOW_TAMPERED, "Sealed workflow '{name}' was not started: skill(s) {skills} changed since sealing — review them and seal again"),
    SEAL_SIGNATURE_INVALID(ErrorCode.WORKFLOW_TAMPERED, "Sealed workflow '{name}' was not started: its signature does not match — the seal was forged or copied from another workflow"),
    SEAL_SIGNATURE_MISSING(ErrorCode.WORKFLOW_TAMPERED, "Sealed workflow '{name}' was not started: it carries no signature (automation.hardening.require-signed-seal) — seal it again"),
    SEAL_DEFINITION_UNVERIFIABLE(ErrorCode.WORKFLOW_TAMPERED, "Sealed workflow '{name}' was not started: its definition could not be verified ({error})"),
    SEAL_SKILLS_UNVERIFIABLE(ErrorCode.WORKFLOW_TAMPERED, "Sealed workflow '{name}' was not started: its skills could not be verified ({error})"),
    // An imported file whose integrity checksum does not match its content (DefinitionIntegrity, TODO b18939a6)
    POOL_INTEGRITY_MISMATCH(ErrorCode.INVALID_ARGUMENT, "Pool file {file} was not installed: its integrity checksum does not match its content (declared {declared}, computed {actual}) — it was changed after the export"),
    IMPORT_INTEGRITY_MISMATCH(ErrorCode.INVALID_ARGUMENT, "Workflow file '{name}' was not imported: its integrity checksum does not match its content (declared {declared}, computed {actual}) — it was changed or damaged after the export");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private final ErrorCode code;
    private final String template;

    ErrorMessage(ErrorCode code, String template) {
        this.code = code;
        this.template = template;
    }

    /** The English text with the params filled in; a placeholder without value stays as it is. */
    public String format(Map<String, String> params) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder text = new StringBuilder();
        while (matcher.find()) {
            String value = params == null ? null : params.get(matcher.group(1));
            matcher.appendReplacement(text, Matcher.quoteReplacement(value != null ? value : matcher.group()));
        }
        matcher.appendTail(text);
        return text.toString();
    }
}
