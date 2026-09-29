package com.automation.mapper;

import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.StepResultRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Zusammenfassung je Lauf aus zwei Gruppenabfragen: Schrittzahl und Kopf des letzten Outputs. */
@ExtendWith(MockitoExtension.class)
class RunStepSummariesTest {

    @Mock private StepResultRepository stepResultRepository;

    private RunStepSummaries summaries() {
        return new RunStepSummaries(stepResultRepository);
    }

    @Test
    void emptyOrIdlessRunList_queriesNothing() {
        assertThat(summaries().of(List.of())).isEmpty();
        assertThat(summaries().of(List.of(new WorkflowRun()))).isEmpty();

        verifyNoInteractions(stepResultRepository);
    }

    @Test
    void combinesCountAndLastStepPerRun_andOmitsRunsWithoutSteps() {
        when(stepResultRepository.countStepsByRunIds(any())).thenReturn(rows(
                new Object[]{"r-1", 3L}, new Object[]{"r-2", 1L}));
        when(stepResultRepository.findLastStepHeadsByRunIds(any(), anyInt())).thenReturn(rows(
                new Object[]{"r-1", StepStatus.COMPLETED, "fertig"},
                new Object[]{"r-2", StepStatus.FAILED, null}));

        Map<String, RunStepSummary> result = summaries().of(List.of(run("r-1"), run("r-2"), run("r-3")));

        assertThat(result).containsOnlyKeys("r-1", "r-2");
        assertThat(result.get("r-1")).isEqualTo(new RunStepSummary(3, StepStatus.COMPLETED, "fertig"));
        assertThat(result.get("r-2")).isEqualTo(new RunStepSummary(1, StepStatus.FAILED, null));
    }

    @Test
    void passesDistinctRunIdsAndTheHeadLength() {
        when(stepResultRepository.countStepsByRunIds(any())).thenReturn(List.of());
        when(stepResultRepository.findLastStepHeadsByRunIds(any(), anyInt())).thenReturn(List.of());

        summaries().of(List.of(run("r-1"), run("r-1"), new WorkflowRun(), run("r-2")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(stepResultRepository).countStepsByRunIds(ids.capture());
        assertThat(Set.copyOf(ids.getValue())).containsExactlyInAnyOrder("r-1", "r-2");
        assertThat(ids.getValue()).hasSize(2);
        verify(stepResultRepository).findLastStepHeadsByRunIds(any(), eq(RunStepSummary.HEAD_LENGTH));
    }

    @Test
    void lastStepWithoutCountRow_countsAsZero() {
        when(stepResultRepository.countStepsByRunIds(any())).thenReturn(List.of());
        when(stepResultRepository.findLastStepHeadsByRunIds(any(), anyInt())).thenReturn(rows(
                new Object[]{"r-1", StepStatus.COMPLETED, "x"}));

        assertThat(summaries().of(List.of(run("r-1"))).get("r-1").count()).isZero();
    }

    private static List<Object[]> rows(Object[]... rows) {
        return new ArrayList<>(List.of(rows));
    }

    private static WorkflowRun run(String id) {
        return WorkflowRun.builder().id(id).build();
    }
}
