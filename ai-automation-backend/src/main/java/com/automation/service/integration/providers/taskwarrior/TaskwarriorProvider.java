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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.automation.service.integration.providers.taskwarrior.TaskwarriorArgs.*;

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
        return TaskwarriorActionCatalog.actions();
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
}
