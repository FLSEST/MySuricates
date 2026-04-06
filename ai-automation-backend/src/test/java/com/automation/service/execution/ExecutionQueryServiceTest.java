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
        when(runRepository.findFiltered(isNull(), isNull(), isNull(), isNull(), any(Pageable.class))).thenReturn(expected);

        Page<WorkflowRun> result = service.listExecutions(0, 10, null, null, null, null);

        assertThat(result).isEqualTo(expected);
        verify(runRepository).findFiltered(null, null, null, null, PageRequest.of(0, 10));
    }

    @Test
    void listExecutions_blankStrings_treatedAsNoFilter() {
        Page<WorkflowRun> expected = new PageImpl<>(List.of());
        when(runRepository.findFiltered(isNull(), isNull(), isNull(), isNull(), any(Pageable.class))).thenReturn(expected);

        Page<WorkflowRun> result = service.listExecutions(0, 5, "  ", "  ", "  ", "  ");

        assertThat(result).isEqualTo(expected);
        verify(runRepository).findFiltered(null, null, null, null, PageRequest.of(0, 5));
    }

    // --- listExecutions: workflowId filter ---

    @Test
    void listExecutions_withWorkflowId_filtersByWorkflow() {
        Page<WorkflowRun> expected = new PageImpl<>(List.of(new WorkflowRun()));
        when(runRepository.findFiltered(isNull(), eq("wf-1"), isNull(), isNull(), any(Pageable.class))).thenReturn(expected);

        Page<WorkflowRun> result = service.listExecutions(0, 10, null, "wf-1", null, null);

        assertThat(result).isEqualTo(expected);
        verify(runRepository).findFiltered(null, "wf-1", null, null, PageRequest.of(0, 10));
    }

    @Test
    void listExecutions_allFiltersCombined() {
        Page<WorkflowRun> expected = new PageImpl<>(List.of());
        when(runRepository.findFiltered(eq(RunStatus.RUNNING), eq("wf-1"), eq("proj-1"), isNull(), any(Pageable.class))).thenReturn(expected);

        service.listExecutions(0, 10, "RUNNING", "wf-1", "proj-1", null);

        verify(runRepository).findFiltered(RunStatus.RUNNING, "wf-1", "proj-1", null, PageRequest.of(0, 10));
    }

    // --- listExecutions: projectId filter ---

    @Test
    void listExecutions_withProjectIdOnly_filtersByProject() {
        Page<WorkflowRun> expected = new PageImpl<>(List.of());
        when(runRepository.findFiltered(isNull(), isNull(), eq("proj-1"), isNull(), any(Pageable.class))).thenReturn(expected);

        Page<WorkflowRun> result = service.listExecutions(0, 20, null, null, "proj-1", null);

        assertThat(result).isEqualTo(expected);
        verify(runRepository).findFiltered(null, null, "proj-1", null, PageRequest.of(0, 20));
    }

    @Test
    void listExecutions_withProjectIdAndStatus_filtersByBoth() {
        Page<WorkflowRun> expected = new PageImpl<>(List.of());
        when(runRepository.findFiltered(eq(RunStatus.FAILED), isNull(), eq("proj-1"), isNull(), any(Pageable.class))).thenReturn(expected);

        Page<WorkflowRun> result = service.listExecutions(0, 10, "FAILED", null, "proj-1", null);

        assertThat(result).isEqualTo(expected);
        verify(runRepository).findFiltered(RunStatus.FAILED, null, "proj-1", null, PageRequest.of(0, 10));
    }

    // --- listExecutions: status filter only ---

    @Test
    void listExecutions_withStatusOnly_filtersByStatus() {
        Page<WorkflowRun> expected = new PageImpl<>(List.of());
        when(runRepository.findFiltered(eq(RunStatus.RUNNING), isNull(), isNull(), isNull(), any(Pageable.class))).thenReturn(expected);

        Page<WorkflowRun> result = service.listExecutions(1, 5, "RUNNING", null, null, null);

        assertThat(result).isEqualTo(expected);
        verify(runRepository).findFiltered(RunStatus.RUNNING, null, null, null, PageRequest.of(1, 5));
    }

    @Test
    void listExecutions_statusIsCaseInsensitive() {
        Page<WorkflowRun> expected = new PageImpl<>(List.of());
        when(runRepository.findFiltered(eq(RunStatus.COMPLETED), isNull(), isNull(), isNull(), any(Pageable.class))).thenReturn(expected);

        service.listExecutions(0, 10, "completed", null, null, null);

        verify(runRepository).findFiltered(RunStatus.COMPLETED, null, null, null, PageRequest.of(0, 10));
    }

    @Test
    void listExecutions_invalidStatus_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> service.listExecutions(0, 10, "INVALID", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- listExecutions: search filter ---

    @Test
    void listExecutions_withSearch_passesSearchToRepository() {
        Page<WorkflowRun> expected = new PageImpl<>(List.of());
        when(runRepository.findFiltered(isNull(), isNull(), isNull(), eq("radar"), any(Pageable.class))).thenReturn(expected);

        Page<WorkflowRun> result = service.listExecutions(0, 10, null, null, null, "radar");

        assertThat(result).isEqualTo(expected);
        verify(runRepository).findFiltered(null, null, null, "radar", PageRequest.of(0, 10));
    }

    @Test
    void listExecutions_withSearchAndStatus_combinesBoth() {
        Page<WorkflowRun> expected = new PageImpl<>(List.of());
        when(runRepository.findFiltered(eq(RunStatus.COMPLETED), isNull(), isNull(), eq("test"), any(Pageable.class))).thenReturn(expected);

        service.listExecutions(0, 10, "COMPLETED", null, null, "test");

        verify(runRepository).findFiltered(RunStatus.COMPLETED, null, null, "test", PageRequest.of(0, 10));
    }

    @Test
    void listExecutions_searchWithWhitespace_isTrimmed() {
        Page<WorkflowRun> expected = new PageImpl<>(List.of());
        when(runRepository.findFiltered(isNull(), isNull(), isNull(), eq("radar"), any(Pageable.class))).thenReturn(expected);

        service.listExecutions(0, 10, null, null, null, "  radar  ");

        verify(runRepository).findFiltered(null, null, null, "radar", PageRequest.of(0, 10));
    }

    // --- listExecutions: pagination ---

    @Test
    void listExecutions_passesPageAndSizeCorrectly() {
        when(runRepository.findFiltered(isNull(), isNull(), isNull(), isNull(), any(Pageable.class))).thenReturn(Page.empty());

        service.listExecutions(3, 25, null, null, null, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(runRepository).findFiltered(isNull(), isNull(), isNull(), isNull(), captor.capture());
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
