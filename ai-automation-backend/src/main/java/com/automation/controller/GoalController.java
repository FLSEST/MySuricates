package com.automation.controller;

import com.automation.dto.request.CreateGoalRequest;
import com.automation.dto.request.GoalTodoIdsRequest;
import com.automation.dto.request.UpdateGoalRequest;
import com.automation.dto.response.GoalDetailResponse;
import com.automation.dto.response.GoalRelationsResponse;
import com.automation.dto.response.GoalResponse;
import com.automation.entity.Goal;
import com.automation.service.goal.GoalMembershipService;
import com.automation.service.goal.GoalRelationsService;
import com.automation.service.goal.GoalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Ziele: TODOs projektuebergreifend auf ein Ergebnis hin sammeln, ordnen und den Fortschritt
 * zeigen (TODO dc0566f8). Die Einschaetzung eines einzelnen TODOs laeuft ueber
 * {@code PATCH /api/v1/todos/{id}/planning}.
 */
@RestController
@RequestMapping("/api/v1/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalService goalService;
    private final GoalMembershipService membershipService;
    private final GoalRelationsService relationsService;

    /** Ohne {@code status}: aktive und erledigte Ziele, neueste zuerst. */
    @GetMapping
    public List<GoalResponse> listGoals(@RequestParam(required = false) List<Goal.Status> status) {
        return goalService.list(status);
    }

    @PostMapping
    public ResponseEntity<GoalResponse> createGoal(@Valid @RequestBody CreateGoalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(goalService.create(request));
    }

    @GetMapping("/{id}")
    public GoalDetailResponse getGoal(@PathVariable String id) {
        return goalService.detail(id);
    }

    /**
     * Relationskarte (TODO c6ea8f5c): je TODO Herkunft, Bearbeitung (Fix-Workflow, passende
     * TodoTrigger) und Ergebnis. {@code include} holt bis zu 20 noch nicht zugeordnete TODOs als
     * Kandidaten dazu ({@code candidate: true}).
     */
    @GetMapping("/{id}/relations")
    public GoalRelationsResponse getRelations(@PathVariable String id,
                                              @RequestParam(required = false) List<String> include) {
        return relationsService.relations(id, include);
    }

    @PutMapping("/{id}")
    public GoalResponse updateGoal(@PathVariable String id, @RequestBody UpdateGoalRequest request) {
        return goalService.update(id, request);
    }

    /** Loescht das Ziel; seine TODOs bleiben und verlieren nur die Zuordnung. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGoal(@PathVariable String id) {
        goalService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Ordnet TODOs dem Ziel zu (ohne Rang – sie stehen hinter den eingeordneten). */
    @PostMapping("/{id}/todos")
    public GoalDetailResponse assignTodos(@PathVariable String id, @Valid @RequestBody GoalTodoIdsRequest request) {
        return membershipService.assign(id, request.todoIds());
    }

    /** Reihenfolge: die genannten TODOs bekommen Rang 1..n, alle uebrigen des Ziels keinen. */
    @PutMapping("/{id}/order")
    public GoalDetailResponse reorderTodos(@PathVariable String id, @Valid @RequestBody GoalTodoIdsRequest request) {
        return membershipService.reorder(id, request.todoIds());
    }
}
