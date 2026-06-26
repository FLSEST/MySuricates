package com.automation.service.integration.providers.taskwarrior;

import com.automation.service.cli.TaskCommandService;
import com.automation.service.cli.TaskCommandService.TaskResult;
import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Integration provider for the local Taskwarrior ({@code task}) CLI.
 *
 * <p>Unlike the HTTP-based providers, Taskwarrior is a <em>local command-line tool</em>: actions
 * shell out to the {@code task} binary on the host (via {@link TaskCommandService}) rather than
 * calling a remote API. There is no OAuth — {@link #requiresOAuthFlow()} is {@code false} and the
 * stored credentials encode optional {@code TASKDATA}/{@code TASKRC} paths as {@code "TASKDATA|TASKRC"}.
 *
 * <p>Security: all values are passed as discrete process arguments (never through a shell). Free-text
 * description/annotation values are placed after a {@code --} separator so Taskwarrior treats them
 * literally. Caller-supplied filter tokens are screened against {@code rc.} overrides and Taskwarrior
 * command keywords (notably {@code execute}) to prevent them from being reinterpreted as commands.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskwarriorProvider implements IntegrationProvider {

    /** Accepts Taskwarrior UUIDs (hex + dashes) and short numeric ids. */
    private static final Pattern ID_PATTERN = Pattern.compile("^[0-9a-fA-F-]+$");

    /**
     * Taskwarrior command keywords that must never appear in a caller-supplied filter, since
     * Taskwarrior scans the whole command line for a command and the first match wins —
     * {@code execute} would run an arbitrary host command. Compared case-insensitively, exact-token.
     */
    private static final Set<String> FORBIDDEN_FILTER_TOKENS = Set.of(
            "execute", "import", "config", "undo", "purge", "edit", "sync", "context",
            "add", "delete", "done", "modify", "annotate", "denotate", "duplicate", "log"
    );

    private static final Set<String> VALID_PRIORITIES = Set.of("H", "M", "L");

    private final TaskCommandService taskCommandService;
    private final ObjectMapper objectMapper;

    @Override
    public String getProviderId() {
        return "taskwarrior";
    }

    @Override
    public String getDisplayName() {
        return "Taskwarrior";
    }

    @Override
    public String getIcon() {
        return "ListTodo";
    }

    @Override
    public String getDescription() {
        return "Lokale Taskwarrior-Aufgaben lesen, anlegen, ändern und abschließen";
    }

    @Override
    public boolean requiresOAuthFlow() {
        return false;
    }

    @Override
    public boolean requiresCredentials() {
        // TASKDATA/TASKRC are optional — empty connects against the host's default ~/.task / ~/.taskrc.
        return false;
    }

    @Override
    public OAuthConfig getOAuthConfig() {
        // URLs/scopes are unused for non-OAuth providers; the env-var slots map to the
        // optional TASKDATA / TASKRC paths the user can supply when connecting.
        return new OAuthConfig(
                "", "",
                List.of(),
                "TASKDATA",
                "TASKRC"
        );
    }

    @Override
    public String fetchAccountInfo(String combinedToken) {
        // combinedToken = "TASKDATA|TASKRC" (either side may be empty -> host defaults).
        Map<String, String> env = envFrom(combinedToken);
        try {
            // `_version` is the script-friendly variant — `--version` breaks under rc.verbose=nothing on TW 2.x.
            TaskResult version = taskCommandService.run(List.of("_version"), env, 10);
            if (!version.isSuccess()) {
                throw new IllegalStateException("`task _version` exited " + version.exitCode());
            }
            String versionStr = version.output() != null ? version.output().trim() : "";

            int pending = 0;
            try {
                TaskResult count = taskCommandService.run(List.of("status:pending", "count"), env, 10);
                if (count.isSuccess()) {
                    pending = parseLeadingInt(count.output());
                }
            } catch (RuntimeException e) {
                log.debug("Taskwarrior pending count unavailable: {}", e.getMessage());
            }

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", "Taskwarrior " + versionStr);
            info.put("version", versionStr);
            info.put("dataLocation", env.getOrDefault("TASKDATA", "default (~/.task)"));
            info.put("pendingCount", pending);
            return objectMapper.writeValueAsString(info);
        } catch (Exception e) {
            // Returning null marks the connection DISCONNECTED ("Setup required") in the UI —
            // typically because `task` is not installed on the host.
            log.warn("Failed to validate Taskwarrior: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<IntegrationAction> getAvailableActions() {
        return List.of(
                new IntegrationAction("list_tasks", "Aufgaben auflisten (optional gefiltert)",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "filter", Map.of("type", "string",
                                                "description", "Taskwarrior-Filter, z.B. 'status:pending', 'project:Haus +OVERDUE' (Standard: status:pending)")
                                )
                        )),
                new IntegrationAction("get_task", "Eine einzelne Aufgabe abrufen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "id", Map.of("type", "string", "description", "Aufgaben-UUID oder numerische ID")
                                ),
                                "required", List.of("id")
                        )),
                new IntegrationAction("add_task", "Eine neue Aufgabe anlegen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "description", Map.of("type", "string", "description", "Aufgabentext"),
                                        "project", Map.of("type", "string", "description", "Projektname"),
                                        "priority", Map.of("type", "string", "description", "Priorität: H, M oder L"),
                                        "due", Map.of("type", "string", "description", "Fälligkeit, z.B. 'tomorrow', '2026-07-01'"),
                                        "tags", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Tags ohne '+'")
                                ),
                                "required", List.of("description")
                        )),
                new IntegrationAction("complete_task", "Eine Aufgabe als erledigt markieren",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "id", Map.of("type", "string", "description", "Aufgaben-UUID oder numerische ID")
                                ),
                                "required", List.of("id")
                        )),
                new IntegrationAction("modify_task", "Eine Aufgabe ändern",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "id", Map.of("type", "string", "description", "Aufgaben-UUID oder numerische ID"),
                                        "description", Map.of("type", "string", "description", "Neuer Aufgabentext"),
                                        "project", Map.of("type", "string", "description", "Projektname"),
                                        "priority", Map.of("type", "string", "description", "Priorität: H, M oder L"),
                                        "due", Map.of("type", "string", "description", "Fälligkeit"),
                                        "tags", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Tags ohne '+'")
                                ),
                                "required", List.of("id")
                        )),
                new IntegrationAction("annotate_task", "Eine Notiz zu einer Aufgabe hinzufügen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "id", Map.of("type", "string", "description", "Aufgaben-UUID oder numerische ID"),
                                        "text", Map.of("type", "string", "description", "Notiztext")
                                ),
                                "required", List.of("id", "text")
                        )),
                new IntegrationAction("delete_task", "Eine Aufgabe löschen",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "id", Map.of("type", "string", "description", "Aufgaben-UUID oder numerische ID")
                                ),
                                "required", List.of("id")
                        )),
                new IntegrationAction("list_projects", "Alle Projekte auflisten",
                        Map.of("type", "object", "properties", Map.of())),
                new IntegrationAction("sync_task", "Mit dem konfigurierten Taskwarrior-Sync-Server synchronisieren",
                        Map.of("type", "object", "properties", Map.of()))
        );
    }

    @Override
    public Object executeAction(String action, Map<String, Object> params, String accessToken) {
        Map<String, String> env = envFrom(accessToken);
        return switch (action) {
            case "list_tasks" -> listTasks(params, env);
            case "get_task" -> getTask(params, env);
            case "add_task" -> addTask(params, env);
            case "complete_task" -> completeTask(params, env);
            case "modify_task" -> modifyTask(params, env);
            case "annotate_task" -> annotateTask(params, env);
            case "delete_task" -> deleteTask(params, env);
            case "list_projects" -> listProjects(env);
            case "sync_task" -> syncTask(env);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    // --- Actions ---

    private Object listTasks(Map<String, Object> params, Map<String, String> env) {
        String filter = paramString(params, "filter", "status:pending");
        List<String> filterTokens = sanitizeFilterTokens(filter);
        return taskCommandService.exportJson(filterTokens, env);
    }

    private Object getTask(Map<String, Object> params, Map<String, String> env) {
        String id = requireId(params);
        List<Map<String, Object>> tasks = taskCommandService.exportJson(List.of(id), env);
        if (tasks.isEmpty()) {
            throw new IllegalStateException("No task found for id: " + id);
        }
        return tasks.get(0);
    }

    private Object addTask(Map<String, Object> params, Map<String, String> env) {
        String description = requireParam(params, "description");

        List<String> args = new ArrayList<>();
        args.add("add");
        args.addAll(buildAttributeArgs(params));
        args.add("--");
        args.add(description);

        runWrite(args, env, "add task");
        // The freshly created task is matched by Taskwarrior's built-in +LATEST virtual tag.
        List<Map<String, Object>> created = taskCommandService.exportJson(List.of("+LATEST"), env);
        return created.isEmpty() ? Map.of("created", true) : created.get(0);
    }

    private Object completeTask(Map<String, Object> params, Map<String, String> env) {
        String id = requireId(params);
        runWrite(List.of(id, "done"), env, "complete task");
        return Map.of("completed", true, "id", id);
    }

    private Object modifyTask(Map<String, Object> params, Map<String, String> env) {
        String id = requireId(params);

        List<String> args = new ArrayList<>();
        args.add(id);
        args.add("modify");
        args.addAll(buildAttributeArgs(params));
        if (params.containsKey("description")) {
            String desc = paramString(params, "description", "").trim();
            if (!desc.isEmpty()) {
                args.add("--");
                args.add(desc);
            }
        }
        if (args.size() == 2) {
            throw new IllegalArgumentException("modify_task requires at least one field to change");
        }

        runWrite(args, env, "modify task");
        return exportFirstOrStatus(id, env, "modified");
    }

    private Object annotateTask(Map<String, Object> params, Map<String, String> env) {
        String id = requireId(params);
        String text = requireParam(params, "text");
        runWrite(List.of(id, "annotate", "--", text), env, "annotate task");
        return exportFirstOrStatus(id, env, "annotated");
    }

    private Object deleteTask(Map<String, Object> params, Map<String, String> env) {
        String id = requireId(params);
        runWrite(List.of(id, "delete"), env, "delete task");
        return Map.of("deleted", true, "id", id);
    }

    private Object listProjects(Map<String, String> env) {
        TaskResult result = taskCommandService.run(List.of("_projects"), env, null);
        if (!result.isSuccess()) {
            throw new IllegalStateException("Taskwarrior _projects failed (exit " + result.exitCode() + "): " + result.output());
        }
        List<String> projects = new ArrayList<>();
        String out = result.output();
        if (out != null && !out.isBlank()) {
            for (String line : out.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    projects.add(trimmed);
                }
            }
        }
        return projects;
    }

    private Object syncTask(Map<String, String> env) {
        TaskResult result = taskCommandService.run(List.of("sync"), env, null);
        if (!result.isSuccess()) {
            throw new IllegalStateException("Taskwarrior sync failed (exit " + result.exitCode() + "): " + result.output());
        }
        return Map.of("synced", true, "output", result.output());
    }

    // --- Helpers ---

    private void runWrite(List<String> args, Map<String, String> env, String label) {
        TaskResult result = taskCommandService.run(args, env, null);
        if (!result.isSuccess()) {
            throw new IllegalStateException("Taskwarrior " + label + " failed (exit "
                    + result.exitCode() + "): " + result.output());
        }
    }

    private Object exportFirstOrStatus(String id, Map<String, String> env, String statusFlag) {
        List<Map<String, Object>> tasks = taskCommandService.exportJson(List.of(id), env);
        if (!tasks.isEmpty()) {
            return tasks.get(0);
        }
        return Map.of(statusFlag, true, "id", id);
    }

    /** Builds {@code project:} / {@code priority:} / {@code due:} / {@code +tag} attribute args. */
    private List<String> buildAttributeArgs(Map<String, Object> params) {
        List<String> args = new ArrayList<>();

        if (params.containsKey("project")) {
            String p = paramString(params, "project", "").trim();
            if (!p.isEmpty()) {
                args.add("project:" + p);
            }
        }
        if (params.containsKey("priority")) {
            String pr = paramString(params, "priority", "").trim().toUpperCase();
            if (!pr.isEmpty()) {
                if (!VALID_PRIORITIES.contains(pr)) {
                    throw new IllegalArgumentException("Invalid priority: " + pr + " (expected H, M or L)");
                }
                args.add("priority:" + pr);
            }
        }
        if (params.containsKey("due")) {
            String d = paramString(params, "due", "").trim();
            if (!d.isEmpty()) {
                args.add("due:" + d);
            }
        }
        for (String tag : extractTags(params.get("tags"))) {
            args.add("+" + tag);
        }
        return args;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractTags(Object tagsObj) {
        List<String> tags = new ArrayList<>();
        if (tagsObj instanceof List<?> list) {
            for (Object t : list) {
                addTag(tags, String.valueOf(t));
            }
        } else if (tagsObj instanceof String s) {
            for (String t : s.split(",")) {
                addTag(tags, t);
            }
        }
        return tags;
    }

    private void addTag(List<String> tags, String raw) {
        String tag = raw.trim();
        if (tag.startsWith("+") || tag.startsWith("-")) {
            tag = tag.substring(1).trim();
        }
        if (!tag.isEmpty()) {
            // Tags can't contain whitespace in Taskwarrior; reject to avoid silent arg splitting surprises.
            if (tag.matches(".*\\s.*")) {
                throw new IllegalArgumentException("Invalid tag (whitespace not allowed): " + raw);
            }
            tags.add(tag);
        }
    }

    /**
     * Splits a free-text filter into tokens and rejects anything that could be reinterpreted as a
     * Taskwarrior command or an {@code rc.} override.
     */
    private List<String> sanitizeFilterTokens(String filter) {
        List<String> tokens = new ArrayList<>();
        if (filter == null || filter.isBlank()) {
            return tokens;
        }
        for (String token : filter.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.startsWith("rc.") || token.startsWith("rc:")) {
                throw new IllegalArgumentException("Disallowed filter token (rc. overrides not permitted): " + token);
            }
            if (FORBIDDEN_FILTER_TOKENS.contains(token.toLowerCase())) {
                throw new IllegalArgumentException("Disallowed filter token (Taskwarrior command keyword): " + token);
            }
            tokens.add(token);
        }
        return tokens;
    }

    private Map<String, String> envFrom(String combinedToken) {
        Map<String, String> env = new HashMap<>();
        if (combinedToken != null && !combinedToken.isBlank()) {
            String[] parts = combinedToken.split("\\|", 2);
            if (parts.length >= 1 && !parts[0].isBlank()) {
                env.put("TASKDATA", parts[0].trim());
            }
            if (parts.length >= 2 && !parts[1].isBlank()) {
                env.put("TASKRC", parts[1].trim());
            }
        }
        return env;
    }

    private String requireId(Map<String, Object> params) {
        String id = requireParam(params, "id");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid task id: " + id + " (expected UUID or numeric id)");
        }
        return id;
    }

    private static int parseLeadingInt(String s) {
        if (s == null) {
            return 0;
        }
        String trimmed = s.trim();
        int end = 0;
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        return end == 0 ? 0 : Integer.parseInt(trimmed.substring(0, end));
    }

    private static String paramString(Map<String, Object> params, String key, String defaultValue) {
        Object val = params.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    private static String requireParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null || val.toString().isBlank()) {
            throw new IllegalArgumentException("Required parameter missing: " + key);
        }
        return val.toString();
    }
}
