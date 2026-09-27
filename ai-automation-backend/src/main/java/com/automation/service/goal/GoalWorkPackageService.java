package com.automation.service.goal;

import com.automation.dto.projection.GoalProjectRow;
import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.projection.GoalWithWorkPackages;
import com.automation.dto.projection.WorkPackageTodoCounts;
import com.automation.dto.request.CreateGoalWorkPackageRequest;
import com.automation.dto.request.UpdateGoalWorkPackageRequest;
import com.automation.dto.response.GoalWorkPackageResponse;
import com.automation.entity.GoalWorkPackage;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.GoalMilestoneWorkPackageRepository;
import com.automation.repository.GoalProjectRepository;
import com.automation.repository.GoalRepository;
import com.automation.repository.GoalWorkPackageRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Arbeitspakete eines Ziels anlegen, aendern, ordnen, loeschen und mit Zustand lesen (TODO c60bf914,
 * bis V92 „Meilensteine“, TODO 259f4f66). Zugeordnet werden TODOs ueber
 * {@code PATCH /todos/{id}/planning} ({@link TodoPlanningService}).
 *
 * <p>Jedes Paket gehoert genau einem Projekt des Ziels; der Zeitraum ist optional – Start und Ende
 * gibt es nur zusammen, der Start liegt nie nach dem Ende. Die Reihenfolge innerhalb des Projekts
 * steht in {@code position}. Lesen ist schreibend transaktional: erkennt es ein Paket zum ersten Mal
 * als abgeschlossen, speichert es {@code completedAt} ({@link WorkPackageProgress}).
 */
@Service
@RequiredArgsConstructor
public class GoalWorkPackageService {

    static final int MAX_TITLE = 200;

    private final GoalRepository goalRepository;
    private final GoalWorkPackageRepository workPackageRepository;
    private final TodoRepository todoRepository;
    private final GoalProjectRepository goalProjectRepository;
    private final ProjectRepository projectRepository;
    private final GoalMilestoneWorkPackageRepository milestoneLinkRepository;
    private final Clock clock;

    /**
     * Nach Projekt-Position, dann Position im Projekt; Ziel samt Paketen in einer Abfrage, die Zaehler
     * aus den TODO-Zeilen.
     */
    @Transactional
    public List<GoalWorkPackageResponse> list(String goalId) {
        GoalWithWorkPackages loaded = goalRepository.findWithWorkPackages(goalId)
                .orElseThrow(() -> new ResourceNotFoundException("Goal", goalId));
        List<GoalTodoRow> rows = todoRepository.findGoalRows(loaded.goal().getId());
        return WorkPackageProgress.evaluate(loaded.workPackages(), rows, loaded.projectPositions(), clock.instant());
    }

    /**
     * Neues Paket ans Ende seines Projekts. Ohne {@code projectId} nur, wenn das Ziel genau ein Projekt
     * hat; ein genanntes Projekt, das dem Ziel noch fehlt, kommt dazu. Zeitraum optional.
     */
    @Transactional
    public GoalWorkPackageResponse create(String goalId, CreateGoalWorkPackageRequest request) {
        String title = GoalDates.title(request.title(), MAX_TITLE, "Ein Arbeitspaket");
        Instant start = GoalDates.start(request.startDate());
        Instant end = GoalDates.end(request.endDate());
        requireValidPeriod(start, end);
        if (!goalRepository.existsById(goalId)) {
            throw new ResourceNotFoundException("Goal", goalId);
        }
        String projectId = blankToNull(request.projectId());
        if (projectId == null) {
            List<GoalProjectRow> projects = goalProjectRepository.findRows(goalId);
            if (projects.size() != 1) {
                throw new IllegalArgumentException("Ein Arbeitspaket braucht ein Projekt (projectId) – das Ziel hat "
                        + projects.size() + " Projekte");
            }
            projectId = projects.get(0).projectId();
        } else {
            linkProject(goalId, projectId);
        }
        GoalWorkPackage saved = workPackageRepository.save(GoalWorkPackage.builder()
                .goalId(goalId).projectId(projectId).title(title).startDate(start).endDate(end)
                .position(nextPosition(goalId, projectId)).build());
        return WorkPackageProgress.toResponse(saved, 0, 0, 0, clock.instant());
    }

    /**
     * {@code ""} leert Start oder Ende; das Ergebnis muss beide haben oder keins, Start nie nach dem Ende.
     * Ein Projektwechsel geht nur, solange dem Paket keine TODOs zugeordnet sind (sonst 400); es rueckt
     * ans Ende des neuen Projekts.
     */
    @Transactional
    public GoalWorkPackageResponse update(String goalId, String workPackageId, UpdateGoalWorkPackageRequest request) {
        GoalWorkPackage workPackage = find(goalId, workPackageId);
        if (request.title() != null) {
            workPackage.setTitle(GoalDates.title(request.title(), MAX_TITLE, "Ein Arbeitspaket"));
        }
        Instant start = request.startDate() != null ? GoalDates.start(request.startDate()) : workPackage.getStartDate();
        Instant end = request.endDate() != null ? GoalDates.end(request.endDate()) : workPackage.getEndDate();
        requireValidPeriod(start, end);
        workPackage.setStartDate(start);
        workPackage.setEndDate(end);
        if (request.projectId() != null) {
            String projectId = blankToNull(request.projectId());
            if (projectId == null) {
                throw new IllegalArgumentException("Ein Arbeitspaket braucht ein Projekt");
            }
            if (!projectId.equals(workPackage.getProjectId())) {
                long todos = todoRepository.countByWorkPackageId(workPackage.getId());
                if (todos > 0) {
                    throw new IllegalArgumentException("Das Arbeitspaket hat " + todos
                            + " TODO(s) – das Projekt laesst sich nur bei einem leeren Paket wechseln");
                }
                linkProject(goalId, projectId);
                workPackage.setPosition(nextPosition(goalId, projectId));
                workPackage.setProjectId(projectId);
            }
        }
        GoalWorkPackage saved = workPackageRepository.save(workPackage);
        WorkPackageTodoCounts counts = todoRepository.countByWorkPackage(List.of(saved.getId())).stream()
                .findFirst().orElse(new WorkPackageTodoCounts(saved.getId(), 0, 0));
        return WorkPackageProgress.toResponse(saved, counts.total(), counts.done(), counts.openQuestions(), clock.instant());
    }

    /**
     * Neue Reihenfolge der Pakete EINES Projekts: die genannten in dieser Folge vorn, nicht genannte
     * desselben Projekts in ihrer bisherigen Folge dahinter. Pakete verschiedener Projekte in einer
     * Liste sind ein 400, ein fremdes oder unbekanntes ein 404. Antwort: alle Pakete des Ziels.
     */
    @Transactional
    public List<GoalWorkPackageResponse> reorder(String goalId, List<String> workPackageIds) {
        if (!goalRepository.existsById(goalId)) {
            throw new ResourceNotFoundException("Goal", goalId);
        }
        Map<String, GoalWorkPackage> byId = workPackageRepository.findByGoalId(goalId).stream()
                .collect(Collectors.toMap(GoalWorkPackage::getId, Function.identity()));
        List<GoalWorkPackage> ordered = new ArrayList<>();
        for (String id : new LinkedHashSet<>(workPackageIds)) {
            GoalWorkPackage workPackage = byId.get(id);
            if (workPackage == null) {
                throw new ResourceNotFoundException("GoalWorkPackage", id);
            }
            ordered.add(workPackage);
        }
        String projectId = ordered.get(0).getProjectId();
        if (ordered.stream().anyMatch(p -> !Objects.equals(p.getProjectId(), projectId))) {
            throw new IllegalArgumentException("Umsortieren geht nur innerhalb eines Projekts");
        }
        byId.values().stream()
                .filter(p -> Objects.equals(p.getProjectId(), projectId) && !ordered.contains(p))
                .sorted(WorkPackageProgress.order(Map.of()))
                .forEach(ordered::add);
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setPosition(i);
        }
        workPackageRepository.saveAll(ordered);
        return list(goalId);
    }

    /**
     * Loescht das Paket; seine TODOs bleiben im Ziel und verlieren nur die Zuordnung, Meilensteine
     * verlieren die Verknuepfung.
     */
    @Transactional
    public void delete(String goalId, String workPackageId) {
        GoalWorkPackage workPackage = find(goalId, workPackageId);
        milestoneLinkRepository.unlinkWorkPackage(workPackage.getId());
        todoRepository.detachFromWorkPackage(workPackage.getId(), clock.instant());
        workPackageRepository.deleteById(workPackage.getId());
    }

    /** Ein Paket eines anderen Ziels ist hier so unbekannt wie ein fehlendes: 404. */
    private GoalWorkPackage find(String goalId, String workPackageId) {
        return workPackageRepository.findById(workPackageId)
                .filter(p -> p.getGoalId().equals(goalId))
                .orElseThrow(() -> new ResourceNotFoundException("GoalWorkPackage", workPackageId));
    }

    /** Zeitraum: beide Daten oder keins, der Start nie nach dem Ende. */
    static void requireValidPeriod(Instant start, Instant end) {
        if ((start == null) != (end == null)) {
            throw new IllegalArgumentException("Ein Zeitraum braucht Start und Ende – oder keins von beiden");
        }
        if (start != null && start.isAfter(end)) {
            throw new IllegalArgumentException("Der Start liegt nach dem Ende");
        }
    }

    private void linkProject(String goalId, String projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project", projectId);
        }
        goalProjectRepository.linkIfAbsent(goalId, projectId);
    }

    private int nextPosition(String goalId, String projectId) {
        Integer max = workPackageRepository.maxPosition(goalId, projectId);
        return max == null ? 0 : max + 1;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
