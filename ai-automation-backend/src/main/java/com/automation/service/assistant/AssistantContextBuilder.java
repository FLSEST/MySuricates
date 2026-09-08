package com.automation.service.assistant;

import com.automation.dto.AssistantChatRequest;
import com.automation.entity.Workflow;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.assistant.context.BlockTypesIndexSection;
import com.automation.service.assistant.context.ChainsIndexSection;
import com.automation.service.assistant.context.FailedWorkflowsContextSection;
import com.automation.service.assistant.context.IntegrationsIndexSection;
import com.automation.service.assistant.context.ProjectsContextSection;
import com.automation.service.assistant.context.RecentSessionsContextSection;
import com.automation.service.assistant.context.RunsContextSection;
import com.automation.service.assistant.context.SchedulesContextSection;
import com.automation.service.assistant.context.SkillsIndexSection;
import com.automation.service.assistant.context.TodosContextSection;
import com.automation.service.assistant.context.WishesContextSection;
import com.automation.service.assistant.context.WorkflowMemoryContextSection;
import com.automation.service.assistant.context.WorkflowsIndexSection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the system prompt and user prompt for the assistant chat: classifies the page,
 * selects and orders the platform context sections, and delegates the "Current User Context"
 * block to {@link PageContextRenderer}.
 * Extracted from AssistantService to separate context-building concerns.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssistantContextBuilder {

    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;

    // Extracted context sections
    private final RunsContextSection runsSection;
    private final TodosContextSection todosSection;
    private final WishesContextSection wishesSection;
    private final SchedulesContextSection schedulesSection;
    private final SkillsIndexSection skillsSection;
    private final IntegrationsIndexSection integrationsSection;
    private final ChainsIndexSection chainsSection;
    private final BlockTypesIndexSection blockTypesSection;
    private final FailedWorkflowsContextSection failedWorkflowsSection;
    private final ProjectsContextSection projectsSection;
    private final WorkflowsIndexSection workflowsSection;
    private final WorkflowMemoryContextSection workflowMemorySection;
    private final RecentSessionsContextSection recentSessionsSection;

    private final PageContextRenderer pageContextRenderer;

    // ── Page classification ───────────────────────────────────────────────────

    public enum PageCategory { RUNS, WORKFLOWS, TASKS, WISHES, SCHEDULES, DEBUG, OVERVIEW }

    public PageCategory classifyPage(String page) {
        if (page == null) return PageCategory.OVERVIEW;
        return switch (page) {
            case "run", "runs"                     -> PageCategory.RUNS;
            case "workflow", "workflows", "chains" -> PageCategory.WORKFLOWS;
            case "todos", "project", "projects"    -> PageCategory.TASKS;
            case "wishes", "factory"               -> PageCategory.WISHES;
            case "schedules"                       -> PageCategory.SCHEDULES;
            case "debug"                           -> PageCategory.DEBUG;
            default                                -> PageCategory.OVERVIEW;
        };
    }

    // ── System prompt ────────────────────────────────────────────────────────

    /**
     * Resolves the UI language code ("de"/"en") to a display name used in the
     * "Always answer in X." instruction. Null/blank/unknown falls back to German,
     * the platform default, preserving legacy behaviour for requests without a language.
     */
    String resolveAnswerLanguageName(String languageCode) {
        return "en".equalsIgnoreCase(languageCode) ? "English" : "German";
    }

    public String buildSystemPrompt(AssistantChatRequest.PageContext ctx, String baseInstructions, String language) {
        String page = ctx != null ? ctx.page() : null;
        PageCategory cat = classifyPage(page);

        if (cat == PageCategory.RUNS && PageContextRenderer.resolveRunId(ctx) != null) {
            return buildRunFocusedSystemPrompt(ctx, baseInstructions, language);
        }

        var sb = new StringBuilder(4000);

        // 1. Base instructions from resource file
        if (baseInstructions != null && !baseInstructions.isBlank()) {
            sb.append(baseInstructions).append("\n\n");
        }
        sb.append("Always answer in ").append(resolveAnswerLanguageName(language)).append(".\n\n");

        // 2. Dynamic platform context (default: lean, only page-relevant sections)
        boolean includeRunsSection = cat == PageCategory.OVERVIEW;
        boolean includeFailedWorkflowsSection = cat == PageCategory.DEBUG;
        boolean includeTodosSection = cat == PageCategory.TASKS || cat == PageCategory.OVERVIEW;
        boolean includeWishesSection = cat == PageCategory.WISHES || cat == PageCategory.OVERVIEW;
        boolean includeSchedulesSection = cat == PageCategory.SCHEDULES || cat == PageCategory.OVERVIEW;
        boolean includeProjects = cat == PageCategory.WORKFLOWS || cat == PageCategory.TASKS || cat == PageCategory.OVERVIEW;
        boolean compactProjects = cat == PageCategory.TASKS;
        boolean includeWorkflows = cat == PageCategory.WORKFLOWS || cat == PageCategory.TASKS || cat == PageCategory.OVERVIEW;
        boolean compactWorkflows = cat == PageCategory.TASKS;
        boolean includeRepertoire = cat == PageCategory.WORKFLOWS || cat == PageCategory.OVERVIEW;
        boolean compactRepertoire = cat != PageCategory.OVERVIEW;
        boolean includeMemoryAndSessions = cat == PageCategory.OVERVIEW;

        List<Workflow> allWorkflows = includeProjects || includeWorkflows || includeMemoryAndSessions
                ? safeLoadWorkflows()
                : List.of();
        Map<String, Workflow> workflowById = allWorkflows.stream()
                .collect(Collectors.toMap(Workflow::getId, Function.identity(), (a, b) -> a));

        if (includeRunsSection) {
            runsSection.append(sb, false);
        }
        if (includeFailedWorkflowsSection) {
            failedWorkflowsSection.append(sb, false);
        }
        if (includeTodosSection) {
            todosSection.append(sb, cat != PageCategory.OVERVIEW);
        }
        if (includeWishesSection) {
            wishesSection.append(sb, cat != PageCategory.OVERVIEW);
        }
        if (includeSchedulesSection) {
            schedulesSection.append(sb, cat != PageCategory.OVERVIEW);
        }
        if (includeProjects) {
            projectsSection.append(sb, compactProjects, allWorkflows);
        }
        if (includeWorkflows) {
            workflowsSection.append(sb, compactWorkflows, allWorkflows, ctx);
        }
        if (includeRepertoire) {
            skillsSection.append(sb, compactRepertoire);
            integrationsSection.append(sb, compactRepertoire);
            chainsSection.append(sb, compactRepertoire);
            blockTypesSection.append(sb, compactRepertoire);
        }
        if (includeMemoryAndSessions) {
            workflowMemorySection.append(sb, workflowById);
            recentSessionsSection.append(sb, false);
        }

        // 3. Page-specific context (enriched with step outputs for run pages)
        if (ctx != null) pageContextRenderer.appendPageContext(sb, ctx);

        return sb.toString();
    }

    private String buildRunFocusedSystemPrompt(AssistantChatRequest.PageContext ctx, String baseInstructions, String language) {
        var sb = new StringBuilder(2000);
        if (baseInstructions != null && !baseInstructions.isBlank()) {
            sb.append(baseInstructions).append("\n\n");
        }
        sb.append("Always answer in ").append(resolveAnswerLanguageName(language)).append(".\n\n");
        pageContextRenderer.appendRunFocusedContext(sb, ctx);
        return sb.toString();
    }

    public String buildPrompt(List<AssistantChatRequest.MessageEntry> history, String currentMessage) {
        if (history == null || history.isEmpty()) {
            return currentMessage;
        }
        var sb = new StringBuilder();
        sb.append("Previous conversation:\n");
        history.forEach(entry -> {
            String speaker = "user".equals(entry.role()) ? "User" : "MySuricates";
            sb.append(speaker).append(": ").append(entry.content()).append("\n");
        });
        sb.append("\nCurrent message:\n").append(currentMessage);
        return sb.toString();
    }

    public String resolveWorkingDir(AssistantChatRequest.PageContext ctx) {
        if (ctx != null && ctx.projectId() != null) {
            try {
                return projectRepository.findById(ctx.projectId())
                        .map(p -> p.getPath() != null ? p.getPath() : System.getProperty("user.home"))
                        .orElse(System.getProperty("user.home"));
            } catch (Exception ignored) {}
        }
        if (ctx != null && ctx.workflowId() != null) {
            try {
                return workflowRepository.findByIdWithSkills(ctx.workflowId())
                        .map(Workflow::getProject)
                        .map(project -> project != null && project.getPath() != null ? project.getPath() : System.getProperty("user.home"))
                        .orElse(System.getProperty("user.home"));
            } catch (Exception ignored) {}
        }
        return System.getProperty("user.home");
    }

    private List<Workflow> safeLoadWorkflows() {
        try {
            return workflowRepository.findAllWithActiveProject();
        } catch (Exception e) {
            log.debug("Could not load workflows for assistant context", e);
            return List.of();
        }
    }
}
