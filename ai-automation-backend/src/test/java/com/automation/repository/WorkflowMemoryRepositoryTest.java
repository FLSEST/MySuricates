package com.automation.repository;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class WorkflowMemoryRepositoryTest {

    @Autowired
    private WorkflowMemoryRepository repository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private ProjectRepository projectRepository;

    private String workflowId;
    private String otherWorkflowId;

    @BeforeEach
    void setUp() {
        Project project = projectRepository.save(Project.builder()
                .name("Test Project")
                .type(ProjectType.CODE)
                .path("/tmp/test-project")
                .status(ProjectStatus.STOPPED)
                .build());

        Workflow workflow = workflowRepository.save(Workflow.builder()
                .project(project)
                .name("Test Workflow")
                .build());
        workflowId = workflow.getId();

        Workflow otherWorkflow = workflowRepository.save(Workflow.builder()
                .project(project)
                .name("Other Workflow")
                .build());
        otherWorkflowId = otherWorkflow.getId();
    }

    private WorkflowMemory memory(String wfId, boolean active) {
        return WorkflowMemory.builder()
                .workflowId(wfId)
                .title("Test memory")
                .content("Test content for " + wfId)
                .provider("test-provider")
                .active(active)
                .build();
    }

    private WorkflowMemory memoryWithCreatedAt(String wfId, boolean active, Instant createdAt) {
        return WorkflowMemory.builder()
                .workflowId(wfId)
                .title("Test memory")
                .content("Test content for " + wfId)
                .provider("test-provider")
                .active(active)
                .createdAt(createdAt)
                .build();
    }

    // --- findByWorkflowIdOrderByCreatedAtDesc ---

    @Test
    void findByWorkflowIdOrderByCreatedAtDesc_returnsEntriesForWorkflow() {
        repository.save(memory(workflowId, false));
        repository.save(memory(workflowId, true));
        repository.save(memory(otherWorkflowId, false));

        List<WorkflowMemory> result = repository.findByWorkflowIdOrderByCreatedAtDesc(workflowId);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(m -> workflowId.equals(m.getWorkflowId()));
    }

    @Test
    void findByWorkflowIdOrderByCreatedAtDesc_emptyForUnknownWorkflow() {
        repository.save(memory(workflowId, false));

        List<WorkflowMemory> result = repository.findByWorkflowIdOrderByCreatedAtDesc("no-such-wf");

        assertThat(result).isEmpty();
    }

    @Test
    void findByWorkflowIdOrderByCreatedAtDesc_orderedNewestFirst() {
        Instant older = Instant.now().minusSeconds(60);
        Instant newer = Instant.now();

        repository.save(memoryWithCreatedAt(workflowId, false, older));
        repository.save(memoryWithCreatedAt(workflowId, false, newer));

        List<WorkflowMemory> result = repository.findByWorkflowIdOrderByCreatedAtDesc(workflowId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getCreatedAt()).isAfterOrEqualTo(result.get(1).getCreatedAt());
    }

    // --- findByWorkflowIdAndActiveTrue ---

    @Test
    void findByWorkflowIdAndActiveTrue_returnsActiveMemory() {
        repository.save(memory(workflowId, true));
        repository.save(memory(workflowId, false));

        Optional<WorkflowMemory> result = repository.findByWorkflowIdAndActiveTrue(workflowId);

        assertThat(result).isPresent();
        assertThat(result.get().getWorkflowId()).isEqualTo(workflowId);
        assertThat(result.get().isActive()).isTrue();
    }

    @Test
    void findByWorkflowIdAndActiveTrue_emptyWhenNoActiveMemory() {
        repository.save(memory(workflowId, false));

        Optional<WorkflowMemory> result = repository.findByWorkflowIdAndActiveTrue(workflowId);

        assertThat(result).isEmpty();
    }

    @Test
    void findByWorkflowIdAndActiveTrue_emptyForUnknownWorkflow() {
        repository.save(memory(workflowId, true));

        Optional<WorkflowMemory> result = repository.findByWorkflowIdAndActiveTrue("no-such-wf");

        assertThat(result).isEmpty();
    }

    // --- findTop8ByActiveTrueOrderByCreatedAtDesc: Filter und Reihenfolge ---
    // Die unbeschraenkte findByActiveTrueOrderByCreatedAtDesc ist entfernt (7a8481e2); diese Tests
    // pruefen dieselben Eigenschaften an der Abfrage, die der Assistent tatsaechlich stellt.
    // Zeitstempel weit in der Zukunft, damit Reste anderer Tests nicht in die Top 8 ruecken.

    @Test
    void findTop8ByActiveTrue_returnsOnlyActiveEntries() {
        Instant base = Instant.parse("2999-01-01T00:00:00Z");
        WorkflowMemory active = repository.save(memoryWithCreatedAt(workflowId, true, base.plusSeconds(1)));
        WorkflowMemory inactive = repository.save(memoryWithCreatedAt(workflowId, false, base.plusSeconds(2)));
        WorkflowMemory otherActive = repository.save(memoryWithCreatedAt(otherWorkflowId, true, base));

        List<WorkflowMemory> result = repository.findTop8ByActiveTrueOrderByCreatedAtDesc();

        assertThat(result).allMatch(WorkflowMemory::isActive)
                .extracting(WorkflowMemory::getId)
                .contains(active.getId(), otherActive.getId())
                .doesNotContain(inactive.getId());
    }

    @Test
    void findTop8ByActiveTrue_skipsInactiveEntries_evenWhenTheyAreTheNewest() {
        Instant base = Instant.parse("2999-01-01T00:00:00Z");
        WorkflowMemory first = repository.save(memoryWithCreatedAt(workflowId, false, base));
        WorkflowMemory second = repository.save(memoryWithCreatedAt(otherWorkflowId, false, base.plusSeconds(1)));

        List<WorkflowMemory> result = repository.findTop8ByActiveTrueOrderByCreatedAtDesc();

        assertThat(result).extracting(WorkflowMemory::getId).doesNotContain(first.getId(), second.getId());
    }

    @Test
    void findTop8ByActiveTrue_orderedNewestFirst() {
        Instant base = Instant.parse("2999-01-01T00:00:00Z");
        WorkflowMemory older = repository.save(memoryWithCreatedAt(workflowId, true, base));
        WorkflowMemory newer = repository.save(memoryWithCreatedAt(otherWorkflowId, true, base.plusSeconds(60)));

        List<WorkflowMemory> result = repository.findTop8ByActiveTrueOrderByCreatedAtDesc();

        assertThat(result).extracting(WorkflowMemory::getId).startsWith(newer.getId(), older.getId());
    }

    // --- deactivateAllForWorkflow ---

    @Test
    void deactivateAllForWorkflow_deactivatesActiveEntries() {
        repository.save(memory(workflowId, true));
        repository.save(memory(workflowId, true));
        repository.flush();

        repository.deactivateAllForWorkflow(workflowId);
        repository.flush();

        Optional<WorkflowMemory> result = repository.findByWorkflowIdAndActiveTrue(workflowId);
        assertThat(result).isEmpty();
    }

    @Test
    void deactivateAllForWorkflow_doesNotAffectOtherWorkflows() {
        repository.save(memory(workflowId, true));
        repository.save(memory(otherWorkflowId, true));
        repository.flush();

        repository.deactivateAllForWorkflow(workflowId);
        repository.flush();

        Optional<WorkflowMemory> otherResult = repository.findByWorkflowIdAndActiveTrue(otherWorkflowId);
        assertThat(otherResult).isPresent();
    }

    @Test
    void deactivateAllForWorkflow_noOpWhenNoActiveEntries() {
        repository.save(memory(workflowId, false));
        repository.flush();

        // Should not throw
        repository.deactivateAllForWorkflow(workflowId);
        repository.flush();

        List<WorkflowMemory> result = repository.findByWorkflowIdOrderByCreatedAtDesc(workflowId);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).isActive()).isFalse();
    }

    /** Page suggestions read only the five newest active memories, limited in the query (TODO bdd11037). */
    @Test
    void findTop5ByActiveTrue_returnsTheFiveNewestActiveMemories() {
        Instant base = Instant.parse("2999-01-01T00:00:00Z"); // newer than anything other tests leave behind
        for (int i = 0; i < 7; i++) {
            repository.save(memoryWithCreatedAt(i % 2 == 0 ? workflowId : otherWorkflowId, true, base.plusSeconds(i)));
        }
        repository.save(memoryWithCreatedAt(workflowId, false, base.plusSeconds(100)));

        List<WorkflowMemory> top = repository.findTop5ByActiveTrueOrderByCreatedAtDesc();

        assertThat(top).hasSize(5).allMatch(WorkflowMemory::isActive)
                .extracting(WorkflowMemory::getCreatedAt)
                .containsExactly(base.plusSeconds(6), base.plusSeconds(5), base.plusSeconds(4),
                        base.plusSeconds(3), base.plusSeconds(2));
    }

    /** The assistant's "Learnings & Pitfalls" read only the eight newest active memories, limited in the query (TODO 8c791a45). */
    @Test
    void findTop8ByActiveTrue_returnsTheEightNewestActiveMemories() {
        Instant base = Instant.parse("2999-01-01T00:00:00Z"); // newer than anything other tests leave behind
        for (int i = 0; i < 10; i++) {
            repository.save(memoryWithCreatedAt(i % 2 == 0 ? workflowId : otherWorkflowId, true, base.plusSeconds(i)));
        }
        repository.save(memoryWithCreatedAt(workflowId, false, base.plusSeconds(100)));

        List<WorkflowMemory> top = repository.findTop8ByActiveTrueOrderByCreatedAtDesc();

        assertThat(top).hasSize(8).allMatch(WorkflowMemory::isActive)
                .extracting(WorkflowMemory::getCreatedAt)
                .containsExactly(base.plusSeconds(9), base.plusSeconds(8), base.plusSeconds(7), base.plusSeconds(6),
                        base.plusSeconds(5), base.plusSeconds(4), base.plusSeconds(3), base.plusSeconds(2));
    }
}
