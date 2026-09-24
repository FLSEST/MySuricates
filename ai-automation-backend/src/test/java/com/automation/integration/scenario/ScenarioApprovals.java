package com.automation.integration.scenario;

import com.automation.entity.RunStatus;
import com.automation.executor.ApprovalRequest;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.RunControlService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code answers} of a scenario (TODO 0fc5073b): waits until the run asks, checks the question as
 * the person deciding would see it, and answers the way {@code POST /executions/{id}/approve} does.
 */
final class ScenarioApprovals {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private final RunControlService runControl;
    private final WorkflowRunRepository workflowRunRepository;
    private final EntityManager entityManager;

    ScenarioApprovals(RunControlService runControl, WorkflowRunRepository workflowRunRepository,
                      EntityManager entityManager) {
        this.runControl = runControl;
        this.workflowRunRepository = workflowRunRepository;
        this.entityManager = entityManager;
    }

    void answer(String runId, JsonNode answers) {
        int index = 0;
        for (JsonNode want : answers) {
            String where = "approval #" + index++;
            ApprovalRequest pending = awaitQuestion(runId, where);
            for (JsonNode part : want.path("questionContains")) {
                assertThat(pending.getQuestion()).as(where + " question").contains(part.asText());
            }
            for (JsonNode part : want.path("questionNotContains")) {
                assertThat(pending.getQuestion()).as(where + " question").doesNotContain(part.asText());
            }
            if (want.has("rejectOptions")) {
                List<String> expected = new ArrayList<>();
                want.path("rejectOptions").forEach(option -> expected.add(option.asText()));
                assertThat(pending.getRejectOptions()).as(where + " rejectOptions").containsExactlyElementsOf(expected);
            }
            runControl.resolveApproval(runId, want.path("answer").asText());
            awaitAnswered(runId, pending, where);
        }
    }

    private ApprovalRequest awaitQuestion(String runId, String where) {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            ApprovalRequest pending = runControl.getPendingApproval(runId);
            if (pending != null) {
                return pending;
            }
            entityManager.clear();
            RunStatus status = workflowRunRepository.findById(runId).orElseThrow().getStatus();
            if (status != RunStatus.RUNNING && status != RunStatus.PENDING && status != RunStatus.PAUSED) {
                throw new AssertionError(where + " expected, but the run ended " + status + " without asking");
            }
            ScenarioChecks.sleep(50);
        }
        throw new AssertionError(where + " expected, but the run did not ask within " + WAIT.toSeconds() + " s");
    }

    /** The next approval must not be mistaken for the one just answered. */
    private void awaitAnswered(String runId, ApprovalRequest answered, String where) {
        Instant deadline = Instant.now().plus(WAIT);
        while (runControl.getPendingApproval(runId) == answered) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError(where + " was answered, but the run still waits for it");
            }
            ScenarioChecks.sleep(50);
        }
    }
}
