package com.automation.service.learning;

import com.automation.dto.response.PlatformStatsResponse;
import com.automation.dto.response.TodoCountResponse;
import com.automation.entity.*;
import com.automation.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class PlatformStatsService {

    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;
    private final SkillRepository skillRepository;
    private final WorkflowTemplateRepository templateRepository;
    private final ScheduledWorkflowRepository scheduleRepository;
    private final WebhookTriggerRepository webhookRepository;
    private final WorkflowChainRepository chainRepository;
    private final ChainRunRepository chainRunRepository;
    private final TodoTriggerRepository todoTriggerRepository;
    private final CompletionTriggerRepository completionTriggerRepository;
    private final WorkflowMemoryRepository memoryRepository;
    private final TodoRepository todoRepository;
    private final WishRepository wishRepository;
    private final AssistantSessionRepository assistantSessionRepository;

    @Value("${automation.workflow-pool-path:workflow-pool}")
    private String workflowPoolPath;

    @Transactional(readOnly = true)
    public PlatformStatsResponse getPlatformStats() {
        // Projects
        long activeProjects = projectRepository.findByArchivedAtIsNull().size();
        long archivedProjects = projectRepository.findByArchivedAtIsNotNull().size();

        // Workflows
        long totalWorkflows = workflowRepository.count();
        long workflowPoolSize = countWorkflowPoolFiles();

        // Skills
        List<Skill> allSkills = skillRepository.findAll();
        long totalSkills = allSkills.size();
        long builtinSkills = allSkills.stream().filter(Skill::isBuiltin).count();
        long customSkills = totalSkills - builtinSkills;

        List<PlatformStatsResponse.SkillUsageEntry> topSkillsByUsage = allSkills.stream()
                .map(s -> new PlatformStatsResponse.SkillUsageEntry(
                        s.getId(), s.getName(),
                        s.getCategory() != null ? s.getCategory().name() : "",
                        s.getWorkflows() != null ? s.getWorkflows().size() : 0))
                .filter(e -> e.workflowCount() > 0)
                .sorted(Comparator.comparingLong(PlatformStatsResponse.SkillUsageEntry::workflowCount).reversed())
                .limit(10)
                .toList();

        // Templates
        long totalTemplates = templateRepository.count();
        long builtinTemplates = templateRepository.findByBuiltInTrue().size();
        long customTemplates = totalTemplates - builtinTemplates;

        // Schedules
        long totalSchedules = scheduleRepository.count();
        long activeSchedules = scheduleRepository.findByEnabledTrue().size();

        // Webhooks
        long totalWebhooks = webhookRepository.count();
        long activeWebhooks = webhookRepository.countByEnabledTrue();

        // Chains
        long totalChains = chainRepository.count();
        long totalChainRuns = chainRunRepository.count();
        long successfulChainRuns = chainRunRepository.countByStatus(RunStatus.COMPLETED);
        long failedChainRuns = chainRunRepository.countByStatus(RunStatus.FAILED);
        var chainStats = new PlatformStatsResponse.ChainStats(
                totalChains, totalChainRuns, successfulChainRuns, failedChainRuns);

        // Triggers
        long activeTodoTriggers = todoTriggerRepository.findByEnabledTrue().size();
        long activeCompletionTriggers = completionTriggerRepository.countByEnabledTrue();

        // Memory
        long activeMemoryEntries = memoryRepository.findByActiveTrueOrderByCreatedAtDesc().size();

        // Todos
        TodoCountResponse todoCounts = todoRepository.countGroupedByStatus();
        long fromUser = todoRepository.countBySource(Todo.Source.USER);
        long fromAi = todoRepository.countBySource(Todo.Source.AI);
        long fromWorkflow = todoRepository.countBySource(Todo.Source.WORKFLOW);
        var todoStats = new PlatformStatsResponse.TodoStats(
                todoCounts.open(), todoCounts.inProgress(), todoCounts.done(), todoCounts.backlog(),
                fromUser, fromAi, fromWorkflow);

        // Wishes
        List<Wish> allWishes = wishRepository.findAllByOrderByCreatedAtDesc();
        long wishTotal = allWishes.size();
        long wishCompleted = allWishes.stream().filter(w -> w.getStatus() == WishStatus.COMPLETED).count();
        long wishFailed = allWishes.stream().filter(w -> w.getStatus() == WishStatus.FAILED || w.getStatus() == WishStatus.CANCELLED).count();
        long wishInProgress = allWishes.stream().filter(w -> {
            WishStatus s = w.getStatus();
            return s != WishStatus.COMPLETED && s != WishStatus.FAILED && s != WishStatus.CANCELLED;
        }).count();
        var wishStats = new PlatformStatsResponse.WishStats(wishTotal, wishCompleted, wishFailed, wishInProgress);

        // Assistant
        long totalSessions = assistantSessionRepository.count();
        double avgMessages = 0;
        if (totalSessions > 0) {
            List<AssistantSession> sessions = assistantSessionRepository.findAll();
            avgMessages = sessions.stream()
                    .mapToInt(AssistantSession::getMessageCount)
                    .average()
                    .orElse(0);
        }
        var assistantStats = new PlatformStatsResponse.AssistantStats(totalSessions, avgMessages);

        return new PlatformStatsResponse(
                activeProjects, archivedProjects,
                totalWorkflows, workflowPoolSize,
                totalSkills, builtinSkills, customSkills, topSkillsByUsage,
                totalTemplates, builtinTemplates, customTemplates,
                activeSchedules, totalSchedules,
                activeWebhooks, totalWebhooks,
                chainStats,
                activeTodoTriggers, activeCompletionTriggers,
                activeMemoryEntries,
                todoStats, wishStats, assistantStats
        );
    }

    private long countWorkflowPoolFiles() {
        Path poolDir = Path.of(workflowPoolPath);
        if (!Files.isDirectory(poolDir)) return 0;
        try (Stream<Path> files = Files.list(poolDir)) {
            return files.filter(p -> p.toString().endsWith(".json")).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
