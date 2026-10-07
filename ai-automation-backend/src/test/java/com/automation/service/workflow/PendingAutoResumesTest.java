package com.automation.service.workflow;

import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PendingAutoResumesTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowRepository workflowRepository;

    private PendingAutoResumes pending;
    private Instant beforeCreation;

    @BeforeEach
    void setUp() {
        beforeCreation = Instant.now();
        pending = new PendingAutoResumes(runRepository, workflowRepository);
    }

    private static Workflow workflow(boolean optIn) {
        Map<String, Object> metadata = optIn ? Map.of("autoResume", Map.of("enabled", true)) : Map.of();
        return Workflow.builder().id("wf").name("wf").metadata(new HashMap<>(metadata)).build();
    }

    private static WorkflowRun interrupted(String id, String from) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(RunInterruption.FROM_KEY, from);
        return WorkflowRun.builder().id(id).status(RunStatus.INTERRUPTED)
                .triggerSource(TriggerSource.MANUAL).metadata(metadata).build();
    }

    private void interruptedRuns(WorkflowRun... runs) {
        when(runRepository.findByWorkflowIdAndStatusAndCompletedAtGreaterThanEqual(
                eq("wf"), eq(RunStatus.INTERRUPTED), any(Instant.class))).thenReturn(List.of(runs));
    }

    private void storedWorkflow(Workflow workflow) {
        lenient().when(workflowRepository.findById("wf")).thenReturn(Optional.of(workflow));
    }

    @Test
    void should_beFalse_withoutInterruptedRuns_andNotLookAtTheWorkflow() {
        interruptedRuns();

        assertThat(pending.forWorkflow("wf")).isFalse();
        verify(workflowRepository, never()).findById(anyString());
    }

    @Test
    void should_onlyAskForRunsInterruptedInThisLifetime() {
        interruptedRuns();

        pending.forWorkflow("wf");

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(runRepository).findByWorkflowIdAndStatusAndCompletedAtGreaterThanEqual(
                eq("wf"), eq(RunStatus.INTERRUPTED), since.capture());
        assertThat(since.getValue()).isBetween(beforeCreation, Instant.now());
    }

    @Test
    void should_beTrue_forAnEligibleRunOfAnOptedInWorkflow() {
        interruptedRuns(interrupted("r1", "RUNNING"));
        storedWorkflow(workflow(true));

        assertThat(pending.forWorkflow("wf")).isTrue();
    }

    @Test
    void should_beFalse_when_workflowDidNotOptIn_orIsGone() {
        interruptedRuns(interrupted("r1", "RUNNING"));
        storedWorkflow(workflow(false));
        assertThat(pending.forWorkflow("wf")).isFalse();

        when(workflowRepository.findById("wf")).thenReturn(Optional.empty());
        assertThat(pending.forWorkflow("wf")).isFalse();
    }

    @Test
    void should_beFalse_when_noRunIsEligible() {
        interruptedRuns(interrupted("r1", "PAUSED"));
        storedWorkflow(workflow(true));

        assertThat(pending.forWorkflow("wf")).isFalse();
    }

    @Test
    void should_ignoreDroppedRuns_untilTheyAreReopened() {
        interruptedRuns(interrupted("r1", "RUNNING"));
        storedWorkflow(workflow(true));

        pending.dropped(List.of("r1"));
        assertThat(pending.forWorkflow("wf")).isFalse();

        pending.reopen(List.of("r1"));
        assertThat(pending.forWorkflow("wf")).isTrue();
    }

    @Test
    void should_countTheRunOfTheAttemptUnderWay_evenWithoutOptIn() {
        interruptedRuns(interrupted("r1", "PAUSED"));
        storedWorkflow(workflow(false));

        pending.attemptStarted("r1");

        assertThat(pending.forWorkflow("wf")).isTrue();
        verify(workflowRepository, never()).findById(anyString());
    }

    @Test
    void should_stopCountingARun_onceItsAttemptEnded() {
        interruptedRuns(interrupted("r1", "RUNNING"));
        storedWorkflow(workflow(true));

        pending.attemptStarted("r1");
        pending.attemptEnded("r1");

        assertThat(pending.forWorkflow("wf")).isFalse();
    }

    @Test
    void should_stillCountOtherWaitingRuns_when_oneWasHandled() {
        interruptedRuns(interrupted("r1", "RUNNING"), interrupted("r2", "RUNNING"));
        storedWorkflow(workflow(true));

        pending.dropped(List.of("r1"));

        assertThat(pending.forWorkflow("wf")).isTrue();
    }

    @Test
    void should_notCountAnInFlightRunOfAnotherWorkflow() {
        interruptedRuns(interrupted("r1", "PAUSED"));
        storedWorkflow(workflow(true));

        pending.attemptStarted("other-run");

        assertThat(pending.forWorkflow("wf")).isFalse();
    }
}
