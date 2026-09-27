package com.automation.service.goal.guard;

import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.GoalGuardRequest;
import com.automation.dto.request.UpdateScheduleRequest;
import com.automation.dto.response.GoalResponse;
import com.automation.entity.ChangeSource;
import com.automation.entity.Goal;
import com.automation.entity.Project;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowTemplate;
import com.automation.exception.ResourceNotFoundException;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.GoalProjectRepository;
import com.automation.repository.GoalRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.goal.GoalService;
import com.automation.service.scheduler.SchedulerService;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.WorkflowTemplateService;
import com.automation.util.LocalizedJson;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Der Ziel-Waechter (TODO 259f4f66): je Ziel ein Workflow aus der mitgelieferten Vorlage
 * {@code goal-guard} mit taeglichem Zeitplan. Einschalten legt beim ersten Mal beides an und merkt
 * sich den Workflow am Ziel; spaeter wird nur der Zeitplan wieder aktiv. Ausschalten pausiert den
 * Zeitplan, der Workflow bleibt.
 *
 * <p>Der Workflow entsteht ueber {@link WorkflowService} (Versionszeile mit
 * {@link ChangeSource#GOAL_GUARD}), der Zeitplan ueber {@link SchedulerService}. Beides schnelle
 * DB-Schritte in einer Transaktion – kein KI-, CLI- oder HTTP-Aufruf.
 *
 * <p>Eigenes Paket, weil der Waechter auf {@code service.workflow} zugreift, das seinerseits
 * {@code service.goal} fuer die Kontextvariable {@code goal_status} braucht – so bleibt es eine
 * Richtung ({@code PackageCycleRatchetTest}).
 */
@Service
@RequiredArgsConstructor
public class GoalGuardService {

    public static final String TEMPLATE_ID = "goal-guard";
    /** Taeglich 08:00 (Spring-CRON mit Sekunden). */
    public static final String DEFAULT_CRON = "0 0 8 * * *";
    private static final Map<String, String> NAME_PREFIX = Map.of("de", "Ziel-Wächter: ", "en", "Goal guard: ");
    private static final int MAX_NAME = 200;

    private final GoalRepository goalRepository;
    private final GoalProjectRepository goalProjectRepository;
    private final TodoRepository todoRepository;
    private final ProjectRepository projectRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowService workflowService;
    private final WorkflowTemplateService templateService;
    private final SchedulerService schedulerService;
    private final GoalService goalService;
    private final ModuleRegistry modules;

    @Transactional
    public GoalResponse setGuard(String goalId, GoalGuardRequest request) {
        Goal goal = goalRepository.findById(goalId).orElseThrow(() -> new ResourceNotFoundException("Goal", goalId));
        if (Boolean.TRUE.equals(request.enabled())) {
            switchOn(goal, request);
        } else {
            pause(goal);
        }
        return goalService.get(goal.getId());
    }

    /** Pausiert den Waechter eines Ziels, das geloescht wird; sein Workflow bleibt. */
    @Transactional
    public void pauseForDeletedGoal(String goalId) {
        goalRepository.findById(goalId).ifPresent(this::pause);
    }

    private void switchOn(Goal goal, GoalGuardRequest request) {
        if (!modules.isEnabled(ModuleCatalog.DASHBOARD_REPORT)) {
            throw new IllegalStateException(ModuleCatalog.DASHBOARD_REPORT.disabledMessage("Der Ziel-Wächter"));
        }
        String cron = blankToNull(request.cron());
        if (cron != null && !CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException("Ungueltiger CRON-Ausdruck: " + cron);
        }
        Workflow workflow = existingWorkflow(goal).orElseGet(() -> createWorkflow(goal, request));
        List<ScheduledWorkflow> schedules = schedulerService.findByWorkflowId(workflow.getId());
        if (schedules.isEmpty()) {
            schedulerService.create(workflow.getId(), null, "WORKFLOW", cron != null ? cron : DEFAULT_CRON,
                    workflow.getName(), Map.of("goal_id", goal.getId()), "CRON", null, null, null, null, "SKIP", false);
            return;
        }
        ScheduledWorkflow schedule = schedules.stream().filter(ScheduledWorkflow::isEnabled).findFirst()
                .orElse(schedules.get(0));
        if (cron != null && !cron.equals(schedule.getCronExpression())) {
            schedulerService.update(schedule.getId(), new UpdateScheduleRequest(cron, null, null, null, null, null, null, null));
        }
        if (!schedule.isEnabled()) {
            schedulerService.enable(schedule.getId());
        }
    }

    private void pause(Goal goal) {
        if (goal.getGuardWorkflowId() == null) {
            return;
        }
        schedulerService.findByWorkflowId(goal.getGuardWorkflowId()).stream()
                .filter(ScheduledWorkflow::isEnabled)
                .forEach(schedule -> schedulerService.disable(schedule.getId()));
    }

    /** Der gemerkte Workflow, solange er nicht (soft-)geloescht ist. */
    private Optional<Workflow> existingWorkflow(Goal goal) {
        return goal.getGuardWorkflowId() == null ? Optional.empty()
                : workflowRepository.findById(goal.getGuardWorkflowId());
    }

    private Workflow createWorkflow(Goal goal, GoalGuardRequest request) {
        String lang = LocalizedJson.normalize(request.locale());
        Project project = project(goal, blankToNull(request.projectId()));
        WorkflowTemplate template = templateService.findById(TEMPLATE_ID);
        String name = NAME_PREFIX.get(lang) + goal.getTitle();
        Workflow workflow = workflowService.create(new CreateWorkflowRequest(
                project.getId(),
                name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name,
                LocalizedJson.pick(template.getDescription(), lang),
                template.getIcon(),
                LocalizedJson.blocksFor(template.getI18nSource(), lang, template.getBlocks()),
                List.of(),
                LocalizedJson.instructionsFor(template.getI18nSource(), lang, template.getInstructions()),
                null,
                null,
                TEMPLATE_ID,
                null,
                Map.of("goal_id", goal.getId(), "goal_locale", lang),
                null
        ), ChangeSource.GOAL_GUARD);
        goal.setGuardWorkflowId(workflow.getId());
        goalRepository.save(goal);
        return workflow;
    }

    /**
     * Das genannte Projekt; sonst das erste nicht archivierte Zielprojekt (TODO c60bf914); sonst das
     * mit den meisten (nicht archivierten) TODOs des Ziels, sonst das aelteste aktive Projekt. Jeder
     * Workflow gehoert zu einem Projekt.
     */
    private Project project(Goal goal, String projectId) {
        if (projectId != null) {
            return projectRepository.findById(projectId).filter(p -> p.getArchivedAt() == null)
                    .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
        }
        Optional<Project> first = goalProjectRepository.findRows(goal.getId()).stream()
                .filter(row -> !row.archived())
                .findFirst()
                .flatMap(row -> projectRepository.findById(row.projectId()));
        if (first.isPresent()) {
            return first.get();
        }
        Map<String, Long> counts = todoRepository.findGoalRows(goal.getId()).stream()
                .map(row -> row.projectId())
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        if (!counts.isEmpty()) {
            Optional<Project> busiest = projectRepository.findAllById(counts.keySet()).stream()
                    .filter(p -> p.getArchivedAt() == null)
                    .max(Comparator.comparingLong((Project p) -> counts.getOrDefault(p.getId(), 0L))
                            .thenComparing(Project::getId, Comparator.reverseOrder()));
            if (busiest.isPresent()) {
                return busiest.get();
            }
        }
        return projectRepository.findByArchivedAtIsNull().stream()
                .min(Comparator.comparing(Project::getCreatedAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
                        .thenComparing(Project::getId))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Kein Projekt für den Ziel-Wächter – erst ein Projekt anlegen oder projectId angeben"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
