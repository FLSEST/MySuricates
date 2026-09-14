package com.automation.service.dlq;

import com.automation.entity.DeadLetterEntry;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterRetryServiceTest {

    @Mock private DeadLetterService dlqService;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowExecutor workflowExecutor;
    @InjectMocks private DeadLetterRetryService service;

    @Test
    void retry_reExecutesOriginalWorkflowWithOriginalContext_thenMarksRetried() {
        DeadLetterEntry entry = DeadLetterEntry.builder().id("dlq-1").workflowId("wf-1").workflowRunId("run-1").build();
        DeadLetterEntry retried = DeadLetterEntry.builder().id("dlq-1").status(DeadLetterEntry.Status.RETRIED).build();
        Workflow workflow = Workflow.builder().id("wf-1").build();
        WorkflowRun originalRun = WorkflowRun.builder().id("run-1").inputContext(Map.of("k", "v")).build();
        when(dlqService.findById("dlq-1")).thenReturn(entry);
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(runRepository.findById("run-1")).thenReturn(Optional.of(originalRun));
        when(dlqService.markRetried("dlq-1")).thenReturn(retried);

        DeadLetterEntry result = service.retry("dlq-1");

        verify(workflowExecutor).execute(workflow, Map.of("k", "v"), TriggerSource.RETRY);
        assertThat(result.getStatus()).isEqualTo(DeadLetterEntry.Status.RETRIED);
    }

    @Test
    void retry_withoutWorkflowReference_onlyMarksRetried() {
        DeadLetterEntry entry = DeadLetterEntry.builder().id("dlq-2").build();
        when(dlqService.findById("dlq-2")).thenReturn(entry);
        when(dlqService.markRetried("dlq-2")).thenReturn(entry);

        service.retry("dlq-2");

        verify(workflowExecutor, never()).execute(any(), any(), eq(TriggerSource.RETRY));
        verify(dlqService).markRetried("dlq-2");
    }

    @Test
    void retry_originalRunGone_stillMarksRetried() {
        DeadLetterEntry entry = DeadLetterEntry.builder().id("dlq-3").workflowId("wf-1").workflowRunId("run-gone").build();
        when(dlqService.findById("dlq-3")).thenReturn(entry);
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(Workflow.builder().id("wf-1").build()));
        when(runRepository.findById("run-gone")).thenReturn(Optional.empty());
        when(dlqService.markRetried("dlq-3")).thenReturn(entry);

        service.retry("dlq-3");

        verify(workflowExecutor, never()).execute(any(), any(), eq(TriggerSource.RETRY));
        verify(dlqService).markRetried("dlq-3");
    }
}
