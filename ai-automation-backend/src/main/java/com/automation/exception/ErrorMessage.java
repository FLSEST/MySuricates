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
    RUN_RETENTION_LOCKED(ErrorCode.RUN_DELETION_DISABLED, "Run retention can only be extended while deleting runs is disabled (automation.executions.allow-delete=false): {current} -> {requested} days");

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
