package com.automation.service.goal;

import com.automation.dto.response.PageResponse;
import com.automation.dto.response.TodoResponse;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.GoalRepository;
import com.automation.repository.TodoRepository;
import com.automation.service.todo.TodoResponseAssembler;
import com.automation.service.todo.TodoSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * „Könnte dazugehören“ (TODO c60bf914): offene TODOs ohne Ziel, die zu einem Ziel passen koennten –
 * standardmaessig nur aus den Projekten des Ziels, auf Wunsch aus allen. Eine Seite wie
 * {@code GET /todos}, in derselben Reihenfolge (Prioritaet, dann neueste) und mit denselben
 * gebuendelten Namen; die Projekte des Ziels filtert eine Unterabfrage, nichts wird vorher geladen.
 */
@Service
@RequiredArgsConstructor
public class GoalCandidateService {

    static final List<String> OPEN = List.of(Todo.Status.OPEN.name(), Todo.Status.IN_PROGRESS.name(), Todo.Status.BACKLOG.name());

    private final GoalRepository goalRepository;
    private final TodoRepository todoRepository;
    private final TodoResponseAssembler responseAssembler;

    /** Hat das Ziel keine Projekte, ist die Seite ohne {@code allProjects} leer; unbekanntes Ziel = 404. */
    @Transactional(readOnly = true)
    public PageResponse<TodoResponse> candidates(String goalId, String q, String tag, boolean allProjects, int page, int size) {
        if (!goalRepository.existsById(goalId)) {
            throw new ResourceNotFoundException("Goal", goalId);
        }
        Specification<Todo> spec = TodoSpecifications.listSpec(null, OPEN, null, null, tag, q)
                .and(TodoSpecifications.inGoal(TodoSpecifications.NO_GOAL));
        if (!allProjects) {
            spec = spec.and(TodoSpecifications.inProjectsOfGoal(goalId));
        }
        // unsortiert: die Reihenfolge steckt in der Specification (aa434aee)
        Page<Todo> result = todoRepository.findAll(spec, PageRequest.of(page, size));
        return new PageResponse<>(responseAssembler.toResponses(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }
}
