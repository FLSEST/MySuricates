package com.automation.service.todo;

import com.automation.config.TodoHygieneConfig;
import com.automation.dto.projection.NeedsYouRow;
import com.automation.dto.projection.ProjectCount;
import com.automation.dto.projection.ProjectStatusCounts;
import com.automation.dto.projection.GoalMilestoneSummaryRow;
import com.automation.dto.projection.WorkPackageTodoCounts;
import com.automation.dto.response.MilestoneAttentionItem;
import com.automation.dto.response.NeedsYouItem;
import com.automation.dto.response.ProjectRollup;
import com.automation.dto.response.TodoOverviewResponse;
import com.automation.entity.Goal;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.entity.GoalDueState;
import com.automation.entity.GoalWorkPackage;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalRepository;
import com.automation.repository.GoalWorkPackageRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
    private final GoalWorkPackageRepository workPackageRepository;
    private final GoalRepository goalRepository;
    private final ModuleRegistry modules;

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
     * Dates of active goals that need attention (TODO 259f4f66, c60bf914): milestones that are due in
     * at most three days or already overdue and not reached, and overdue
     * work packages with still open TODOs ({@code kind}). The state comes from the same rule as in
     * the goal view ({@link GoalDueState}); a package without a period is never due. Without candidates
     * it stays at two queries; by date, on a tie the milestone first.
     */
    private List<MilestoneAttentionItem> milestonesDue(Instant now) {
        if (!modules.isEnabled(ModuleCatalog.GOALS)) {
            return List.of(); // Dates belong to module goals (TODO c60bf914): off = no query, no entries
        }
        PageRequest page = PageRequest.of(0, NEEDS_YOU_LIMIT);
        List<GoalMilestoneSummaryRow> milestones = milestoneRepository.findDueForActiveGoals(
                now.plus(GoalDueState.DUE_SOON_WINDOW), page).stream().filter(m -> !m.reached()).toList();
        List<GoalWorkPackage> workPackages = workPackageRepository.findOverdueForActiveGoals(now, page);
        if (milestones.isEmpty() && workPackages.isEmpty()) {
            return List.of();
        }
        Map<String, WorkPackageTodoCounts> counts = workPackages.isEmpty() ? Map.of()
                : todoRepository.countByWorkPackage(workPackages.stream().map(GoalWorkPackage::getId).toList()).stream()
                        .collect(Collectors.toMap(WorkPackageTodoCounts::workPackageId, c -> c));
        Set<String> goalIds = new HashSet<>();
        milestones.forEach(m -> goalIds.add(m.goalId()));
        workPackages.forEach(p -> goalIds.add(p.getGoalId()));
        Map<String, String> goalTitles = goalRepository.findAllById(goalIds).stream()
                .collect(Collectors.toMap(Goal::getId, Goal::getTitle));
        List<MilestoneAttentionItem> items = new ArrayList<>();
        for (GoalMilestoneSummaryRow m : milestones) {
            items.add(new MilestoneAttentionItem(m.id(), m.title(), m.goalId(), goalTitles.get(m.goalId()), m.dueDate(),
                    m.state(now), m.packagesTotal(), m.packagesDone(), MilestoneAttentionItem.Kind.MILESTONE));
        }
        for (GoalWorkPackage p : workPackages) {
            WorkPackageTodoCounts c = counts.getOrDefault(p.getId(), new WorkPackageTodoCounts(p.getId(), 0, 0));
            items.add(new MilestoneAttentionItem(p.getId(), p.getTitle(), p.getGoalId(), goalTitles.get(p.getGoalId()),
                    p.getEndDate(), GoalWorkPackage.stateOf(p.getEndDate(), c.total(), c.done(), now), c.total(), c.done(),
                    MilestoneAttentionItem.Kind.WORK_PACKAGE));
        }
        return items.stream()
                .filter(item -> item.state() == GoalDueState.DUE_SOON || item.state() == GoalDueState.OVERDUE)
                .sorted(Comparator.comparing(MilestoneAttentionItem::dueDate).thenComparing(MilestoneAttentionItem::kind))
                .limit(NEEDS_YOU_LIMIT)
                .toList();
    }

    private static String key(String projectId) {
        return projectId == null ? NO_PROJECT : projectId;
    }
}
