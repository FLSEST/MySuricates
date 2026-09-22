package com.automation.service.workflow;

import com.automation.entity.Workflow;
import com.automation.entity.WorkflowSealEvent;
import com.automation.entity.WorkflowSealEvent.Action;
import com.automation.repository.WorkflowSealEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowSealHistoryTest {

    @Mock private WorkflowSealEventRepository repository;

    private WorkflowSealHistory history;
    private final List<WorkflowSealEvent> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        history = new WorkflowSealHistory(repository);
        stored.clear();
        when(repository.save(any(WorkflowSealEvent.class))).thenAnswer(inv -> {
            WorkflowSealEvent e = inv.getArgument(0);
            e.setId("ev-" + (stored.size() + 1));
            stored.add(e);
            return e;
        });
        when(repository.findTopByOrderByOccurredAtDescIdDesc())
                .thenAnswer(inv -> stored.isEmpty() ? Optional.empty() : Optional.of(stored.get(stored.size() - 1)));
    }

    private static Workflow workflow(String id, String name) {
        return Workflow.builder().id(id).name(name).build();
    }

    @Test
    void recordsWhatHappenedWithItsTimestamp() {
        WorkflowSealEvent event = history.record(workflow("wf-1", "Deploy"), Action.SEALED, "hash-1",
                Map.of("s1", "h1"), "sig");

        assertThat(event.getWorkflowId()).isEqualTo("wf-1");
        assertThat(event.getWorkflowName()).isEqualTo("Deploy"); // the workflow may be renamed or deleted later
        assertThat(event.getAction()).isEqualTo(Action.SEALED);
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getDefinitionHash()).isEqualTo("hash-1");
        assertThat(event.getSkillPins()).isEqualTo("{\"s1\":\"h1\"}");
        assertThat(event.getSignature()).isEqualTo("sig");
        assertThat(event.getPrevEventHash()).isNull();
        assertThat(event.getEventHash()).hasSize(64);
    }

    @Test
    void chainsEntriesAcrossWorkflows() {
        WorkflowSealEvent first = history.record(workflow("wf-1", "Deploy"), Action.SEALED, "h1", Map.of(), null);
        WorkflowSealEvent second = history.record(workflow("wf-2", "Audit"), Action.SEALED, "h2", Map.of(), null);
        WorkflowSealEvent third = history.record(workflow("wf-1", "Deploy"), Action.UNSEALED, "h1", null, null);

        assertThat(second.getPrevEventHash()).isEqualTo(first.getEventHash());
        assertThat(third.getPrevEventHash()).isEqualTo(second.getEventHash());
        when(repository.findAllByOrderByOccurredAtAscIdAsc()).thenReturn(stored);
        assertThat(history.brokenChainEntries()).isEmpty();
    }

    @Test
    void anAlteredEntryBreaksTheChain() {
        history.record(workflow("wf-1", "Deploy"), Action.SEALED, "h1", Map.of(), null);
        history.record(workflow("wf-1", "Deploy"), Action.UNSEALED, "h1", null, null);
        when(repository.findAllByOrderByOccurredAtAscIdAsc()).thenReturn(stored);

        stored.get(0).setAction(Action.UNSEALED); // rewritten outside the application

        // Only the rewritten row is reported: its content no longer matches its own stored hash. The
        // successor still points at that stored hash, so it stays consistent — the altered entry is
        // named exactly once instead of dragging the whole tail along.
        assertThat(history.brokenChainEntries()).containsExactly("ev-1");
    }

    @Test
    void aDeletedEntryBreaksTheChain() {
        history.record(workflow("wf-1", "Deploy"), Action.SEALED, "h1", Map.of(), null);
        history.record(workflow("wf-1", "Deploy"), Action.UNSEALED, "h1", null, null);
        history.record(workflow("wf-1", "Deploy"), Action.SEALED, "h2", Map.of(), null);
        when(repository.findAllByOrderByOccurredAtAscIdAsc()).thenReturn(List.of(stored.get(0), stored.get(2)));

        assertThat(history.brokenChainEntries()).containsExactly("ev-3");
    }
}
