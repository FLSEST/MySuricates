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

    // --- findByActiveTrueOrderByCreatedAtDesc ---

    @Test
    void findByActiveTrueOrderByCreatedAtDesc_returnsOnlyActiveEntries() {
        repository.save(memory(workflowId, true));
        repository.save(memory(workflowId, false));
        repository.save(memory(otherWorkflowId, true));

        List<WorkflowMemory> result = repository.findByActiveTrueOrderByCreatedAtDesc();

        assertThat(result).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result).allMatch(WorkflowMemory::isActive);
    }

    @Test
    void findByActiveTrueOrderByCreatedAtDesc_emptyWhenNoActiveEntries() {
        repository.save(memory(workflowId, false));
        repository.save(memory(otherWorkflowId, false));

        List<WorkflowMemory> result = repository.findByActiveTrueOrderByCreatedAtDesc();

        assertThat(result).isEmpty();
    }

    @Test
    void findByActiveTrueOrderByCreatedAtDesc_orderedNewestFirst() {
        Instant older = Instant.now().minusSeconds(60);
        Instant newer = Instant.now();

        repository.save(memoryWithCreatedAt(workflowId, true, older));
        repository.save(memoryWithCreatedAt(otherWorkflowId, true, newer));

        List<WorkflowMemory> result = repository.findByActiveTrueOrderByCreatedAtDesc();

        assertThat(result).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result.get(0).getCreatedAt()).isAfterOrEqualTo(result.get(1).getCreatedAt());
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
}
