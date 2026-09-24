package com.automation.service.todo;

import com.automation.config.TodoHygieneConfig;
import com.automation.dto.projection.NeedsYouRow;
import com.automation.dto.projection.ProjectCount;
import com.automation.dto.projection.ProjectStatusCounts;
import com.automation.dto.projection.MilestoneTodoCounts;
import com.automation.dto.response.MilestoneAttentionItem;
import com.automation.dto.response.NeedsYouItem;
import com.automation.dto.response.ProjectRollup;
import com.automation.dto.response.TodoOverviewResponse;
import com.automation.entity.Goal;
import com.automation.entity.GoalMilestone;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Assembles the cross-project Projekt-Cockpit overview from a handful of lean
 * GROUP BY queries plus one batched project-name lookup. Intentionally loads no
 * description/context blobs — safe to run on the single SQLite connection (pool-size=1).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TodoOverviewService {

    /** Sentinel key for todos without a project (projectId == null). */
    private static final String NO_PROJECT = "";
    private static final int NEEDS_YOU_LIMIT = 50;

    private final TodoRepository todoRepository;
    private final ProjectRepository projectRepository;
    private final TodoHygieneConfig hygieneConfig;
    private final GoalMilestoneRepository milestoneRepository;
    private final GoalRepository goalRepository;

    @Transactional(readOnly = true)
    public TodoOverviewResponse getOverview() {
        int staleDays = hygieneConfig.getStaleDays();
        Instant now = Instant.now();
        Instant staleBefore = now.minus(staleDays, ChronoUnit.DAYS);

        List<ProjectStatusCounts> statusCounts = todoRepository.aggregateByProject();
        List<ProjectCount> staleCounts = todoRepository.staleCountByProject(staleBefore);
        List<NeedsYouRow> needsYouRows = todoRepository.findNeedsYou(now, PageRequest.of(0, NEEDS_YOU_LIMIT));

        // Batch-load project names (pattern from TodoService.findAll) — no N+1.
        Set<String> projectIds = new HashSet<>();
        statusCounts.forEach(c -> { if (c.projectId() != null) projectIds.add(c.projectId()); });
        needsYouRows.forEach(r -> { if (r.projectId() != null) projectIds.add(r.projectId()); });
        Map<String, String> projectNames = projectIds.isEmpty()
                ? Map.of()
                : projectRepository.findAllById(projectIds).stream()
                    .collect(Collectors.toMap(p -> p.getId(), p -> p.getName()));

        Map<String, Long> staleByProject = staleCounts.stream()
                .collect(Collectors.toMap(c -> key(c.projectId()), ProjectCount::cnt));

        // Per-project needs-you counts + enriched needs-you items.
        Map<String, Long> needsYouByProject = new HashMap<>();
        List<NeedsYouItem> needsYou = needsYouRows.stream().map(r -> {
            needsYouByProject.merge(key(r.projectId()), 1L, Long::sum);
            String projectName = r.projectId() != null ? projectNames.get(r.projectId()) : null;
            long ageDays = r.createdAt() != null ? ChronoUnit.DAYS.between(r.createdAt(), now) : 0L;
            boolean overdue = r.dueDate() != null && r.dueDate().isBefore(now);
            return new NeedsYouItem(r.id(), r.title(), r.projectId(), projectName,
                    r.priority(), r.dueDate(), r.source(), r.createdAt(), r.updatedAt(), ageDays, overdue);
        }).toList();

        List<ProjectRollup> projects = statusCounts.stream()
                // Only surface projects with active work — archive-only projects add no signal.
                .filter(c -> c.open() + c.inProgress() + c.backlog() > 0)
                .map(c -> {
                    String k = key(c.projectId());
                    String projectName = c.projectId() != null
                            ? projectNames.getOrDefault(c.projectId(), c.projectId())
                            : null;
                    return new ProjectRollup(
                            c.projectId(), projectName,
                            c.open(), c.inProgress(), c.done(), c.backlog(), c.archived(),
                            staleByProject.getOrDefault(k, 0L),
                            c.userActive(), c.machineActive(),
                            needsYouByProject.getOrDefault(k, 0L));
                })
                .sorted(Comparator
                        .comparingLong((ProjectRollup r) -> r.needsYouCount()).reversed()
                        .thenComparing(Comparator.comparingLong(
                                (ProjectRollup r) -> r.open() + r.inProgress() + r.backlog()).reversed()))
                .toList();

        return new TodoOverviewResponse(projects, needsYou, now, staleDays, milestonesDue(now));
    }

    /**
     * Meilensteine aktiver Ziele, die in hoechstens drei Tagen faellig oder schon ueberfaellig sind
     * (TODO 259f4f66). Erreichte fallen heraus; der Zustand kommt aus derselben Regel wie in der
     * Zielansicht ({@link GoalMilestone#stateOf}). Ohne Kandidaten bleibt es bei einer Abfrage.
     */
    private List<MilestoneAttentionItem> milestonesDue(Instant now) {
        List<GoalMilestone> due = milestoneRepository.findDueForActiveGoals(
                now.plus(GoalMilestone.DUE_SOON), PageRequest.of(0, NEEDS_YOU_LIMIT));
        if (due.isEmpty()) {
            return List.of();
        }
        Map<String, MilestoneTodoCounts> counts = todoRepository.countByMilestone(
                due.stream().map(GoalMilestone::getId).toList()).stream()
                .collect(Collectors.toMap(MilestoneTodoCounts::milestoneId, c -> c));
        Map<String, String> goalTitles = goalRepository.findAllById(
                due.stream().map(GoalMilestone::getGoalId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Goal::getId, Goal::getTitle));
        return due.stream().map(m -> {
            MilestoneTodoCounts c = counts.getOrDefault(m.getId(), new MilestoneTodoCounts(m.getId(), 0, 0));
            GoalMilestone.State state = GoalMilestone.stateOf(m.getDueDate(), c.total(), c.done(), now);
            return new MilestoneAttentionItem(m.getId(), m.getTitle(), m.getGoalId(), goalTitles.get(m.getGoalId()),
                    m.getDueDate(), state, c.total(), c.done());
        }).filter(item -> item.state() == GoalMilestone.State.DUE_SOON || item.state() == GoalMilestone.State.OVERDUE)
                .toList();
    }

    private static String key(String projectId) {
        return projectId == null ? NO_PROJECT : projectId;
    }
}
