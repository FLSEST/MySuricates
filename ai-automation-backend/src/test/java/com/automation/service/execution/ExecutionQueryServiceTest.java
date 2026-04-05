package com.automation.service.execution;

import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionQueryServiceTest {

    @Mock
    private WorkflowRunRepository runRepository;

    private ExecutionQueryService service;

    @BeforeEach
    void setUp() {
        service = new ExecutionQueryService(runRepository);
    }

    // --- listExecutions: no filters ---

    @Test
    void listExecutions_noFilters_returnsAllOrderedByStartedAt() {
        Page<WorkflowRun> expected = new PageImpl<>(List.of(new WorkflowRun()));
        when(runRepository.findByOrderByStartedAtDesc(any(Pageable.class))).thenReturn(expected);

        Page<WorkflowRun> result = service.listExecutions(0, 10, null, null, null);

        assertThat(result).isEqualTo(expected);
        verify(runRepository).findByOrderByStartedAtDesc(PageRequest.of(0, 10));
    }

    @Test
    void listExecutions_blankStrings_treatedAsNoFilter() {
        Page<WorkflowRun> expected = new PageImpl<>(List.of());
        when(runRepository.findByOrderByStartedAtDesc(any(Pageable.class))).thenReturn(expected);

        Page<WorkflowRun> result = service.listExecutions(0, 5, "  ", "  ", "  ");

        assertThat(result).isEqualTo(expected);
        verify(runRepository).findByOrderByStartedAtDesc(any(Pageable.class));
    }

    // --- listExecutions: workflowId filter (highest priority) ---

    @Test
    void listExecutions_withWorkflowId_filtersbyWorkflow() {
        Page<WorkflowRun> expected = new PageImpl<>(List.of(new WorkflowRun()));
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc(eq("wf-1"), any(Pageable.class))).thenReturn(expected);

        Page<WorkflowRun> result = service.listExecutions(0, 10, null, "wf-1", null);

        assertThat(result).isEqualTo(expected);
        verify(runRepository).findByWorkflowIdOrderByStartedAtDesc("wf-1", PageRequest.of(0, 10));
    }

    @Test
    void listExecutions_workflowIdTakesPrecedenceOverProjectAndStatus() {
        Page<WorkflowRun> expected = new PageImpl<>(List.of());
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc(eq("wf-1"), any(Pageable.class))).thenReturn(expected);

        service.listExecutions(0, 10, "RUNNING", "wf-1", "proj-1");

        verify(runRepository).findByWorkflowIdOrderByStartedAtDesc(eq("wf-1"), any(Pageable.class));
        verify(runRepository, never()).findByStatusAndProjectIdOrderByStartedAtDesc(any(), any(), any());
        verify(runRepository, never()).findByProjectIdOrderByStartedAtDesc(any(String.class), any(Pageable.class));
    }

    // --- listExecutions: projectId filter ---

    @Test
    void listExecutions_withProjectIdOnly_filtersByProject() {
        Page<WorkflowRun> expected = new PageImpl<>(List.of());
        when(runRepository.findByProjectIdOrderByStartedAtDesc(eq("proj-1"), any(Pageable.class))).thenReturn(expected);

        Page<WorkflowRun> result = service.listExecutions(0, 20, null, null, "proj-1");

        assertThat(result).isEqualTo(expected);
        verify(runRepository).findByProjectIdOrderByStartedAtDesc("proj-1", PageRequest.of(0, 20));
    }

    @Test
    void listExecutions_withProjectIdAndStatus_filtersByBoth() {
        Page<WorkflowRun> expected = new PageImpl<>(List.of());
        when(runRepository.findByStatusAndProjectIdOrderByStartedAtDesc(
                eq(RunStatus.FAILED), eq("proj-1"), any(Pageable.class))).thenReturn(expected);

        Page<WorkflowRun> result = service.listExecutions(0, 10, "FAILED", null, "proj-1");

        assertThat(result).isEqualTo(expected);
        verify(runRepository).findByStatusAndProjectIdOrderByStartedAtDesc(
                RunStatus.FAILED, "proj-1", PageRequest.of(0, 10));
    }

    // --- listExecutions: status filter only ---

    @Test
    void listExecutions_withStatusOnly_filtersByStatus() {
        Page<WorkflowRun> expected = new PageImpl<>(List.of());
        when(runRepository.findByStatusOrderByStartedAtDesc(eq(RunStatus.RUNNING), any(Pageable.class))).thenReturn(expected);

        Page<WorkflowRun> result = service.listExecutions(1, 5, "RUNNING", null, null);

        assertThat(result).isEqualTo(expected);
        verify(runRepository).findByStatusOrderByStartedAtDesc(RunStatus.RUNNING, PageRequest.of(1, 5));
    }

    @Test
    void listExecutions_statusIsCaseInsensitive() {
        Page<WorkflowRun> expected = new PageImpl<>(List.of());
        when(runRepository.findByStatusOrderByStartedAtDesc(eq(RunStatus.COMPLETED), any(Pageable.class))).thenReturn(expected);

        service.listExecutions(0, 10, "completed", null, null);

        verify(runRepository).findByStatusOrderByStartedAtDesc(RunStatus.COMPLETED, PageRequest.of(0, 10));
    }

    @Test
    void listExecutions_invalidStatus_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> service.listExecutions(0, 10, "INVALID", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- listExecutions: pagination ---

    @Test
    void listExecutions_passesPageAndSizeCorrectly() {
        when(runRepository.findByOrderByStartedAtDesc(any(Pageable.class))).thenReturn(Page.empty());

        service.listExecutions(3, 25, null, null, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(runRepository).findByOrderByStartedAtDesc(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(3);
        assertThat(captor.getValue().getPageSize()).isEqualTo(25);
    }

    // --- findById ---

    @Test
    void findById_existingRun_returnsRun() {
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));

        WorkflowRun result = service.findById("run-1");

        assertThat(result.getId()).isEqualTo("run-1");
    }

    @Test
    void findById_nonExisting_throwsResourceNotFoundException() {
        when(runRepository.findById("no-such-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById("no-such-id"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Execution")
                .hasMessageContaining("no-such-id");
    }
}
