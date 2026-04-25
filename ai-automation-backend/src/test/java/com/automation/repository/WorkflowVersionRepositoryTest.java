package com.automation.repository;

import com.automation.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class WorkflowVersionRepositoryTest {

    @Autowired
    private WorkflowVersionRepository repository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private ProjectRepository projectRepository;

    private String workflowId;
    private String otherWorkflowId;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        workflowId = saveWorkflow("Workflow A").getId();
        otherWorkflowId = saveWorkflow("Workflow B").getId();
    }

    @Test
    void should_return_empty_page_when_no_versions_exist_for_workflow() {
        Page<WorkflowVersion> page = repository.findByWorkflowIdOrderByVersionNumberDesc(
                workflowId, PageRequest.of(0, 10));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void should_return_versions_ordered_by_version_number_desc() {
        saveVersion(workflowId, 1);
        saveVersion(workflowId, 3);
        saveVersion(workflowId, 2);

        Page<WorkflowVersion> page = repository.findByWorkflowIdOrderByVersionNumberDesc(
                workflowId, PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(WorkflowVersion::getVersionNumber)
                .containsExactly(3, 2, 1);
    }

    @Test
    void should_only_return_versions_for_requested_workflow() {
        saveVersion(workflowId, 1);
        saveVersion(workflowId, 2);
        saveVersion(otherWorkflowId, 99);

        Page<WorkflowVersion> page = repository.findByWorkflowIdOrderByVersionNumberDesc(
                workflowId, PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(WorkflowVersion::getWorkflowId)
                .containsOnly(workflowId);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void should_respect_pagination_parameters() {
        saveVersion(workflowId, 1);
        saveVersion(workflowId, 2);
        saveVersion(workflowId, 3);
        saveVersion(workflowId, 4);
        saveVersion(workflowId, 5);

        Pageable firstPage = PageRequest.of(0, 2);
        Page<WorkflowVersion> page1 = repository.findByWorkflowIdOrderByVersionNumberDesc(workflowId, firstPage);

        assertThat(page1.getContent())
                .extracting(WorkflowVersion::getVersionNumber)
                .containsExactly(5, 4);
        assertThat(page1.getTotalElements()).isEqualTo(5);
        assertThat(page1.getTotalPages()).isEqualTo(3);

        Page<WorkflowVersion> page2 = repository.findByWorkflowIdOrderByVersionNumberDesc(workflowId, PageRequest.of(1, 2));
        assertThat(page2.getContent())
                .extracting(WorkflowVersion::getVersionNumber)
                .containsExactly(3, 2);
    }

    @Test
    void should_return_empty_optional_when_no_version_exists() {
        Optional<WorkflowVersion> latest = repository.findTopByWorkflowIdOrderByVersionNumberDesc(workflowId);

        assertThat(latest).isEmpty();
    }

    @Test
    void should_return_highest_version_number_for_workflow() {
        saveVersion(workflowId, 1);
        saveVersion(workflowId, 5);
        saveVersion(workflowId, 3);

        Optional<WorkflowVersion> latest = repository.findTopByWorkflowIdOrderByVersionNumberDesc(workflowId);

        assertThat(latest).isPresent();
        assertThat(latest.get().getVersionNumber()).isEqualTo(5);
        assertThat(latest.get().getWorkflowId()).isEqualTo(workflowId);
    }

    @Test
    void should_not_return_other_workflows_version_as_latest() {
        saveVersion(otherWorkflowId, 99);

        Optional<WorkflowVersion> latest = repository.findTopByWorkflowIdOrderByVersionNumberDesc(workflowId);

        assertThat(latest).isEmpty();
    }

    @Test
    void should_return_zero_count_when_no_versions_exist() {
        assertThat(repository.countByWorkflowId(workflowId)).isZero();
    }

    @Test
    void should_count_only_versions_for_requested_workflow() {
        saveVersion(workflowId, 1);
        saveVersion(workflowId, 2);
        saveVersion(workflowId, 3);
        saveVersion(otherWorkflowId, 1);
        saveVersion(otherWorkflowId, 2);

        assertThat(repository.countByWorkflowId(workflowId)).isEqualTo(3);
        assertThat(repository.countByWorkflowId(otherWorkflowId)).isEqualTo(2);
    }

    @Test
    void should_return_zero_count_for_unknown_workflow_id() {
        saveVersion(workflowId, 1);

        assertThat(repository.countByWorkflowId("nonexistent-id")).isZero();
    }

    @Test
    void should_persist_and_retrieve_version_with_all_fields() {
        WorkflowVersion saved = saveVersion(workflowId, 42, "autosave", "initial snapshot");

        Optional<WorkflowVersion> retrieved = repository.findById(saved.getId());

        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getWorkflowId()).isEqualTo(workflowId);
        assertThat(retrieved.get().getVersionNumber()).isEqualTo(42);
        assertThat(retrieved.get().getChangeSource()).isEqualTo("autosave");
        assertThat(retrieved.get().getChangeMessage()).isEqualTo("initial snapshot");
        assertThat(retrieved.get().getSnapshot()).isNotBlank();
        assertThat(retrieved.get().getCreatedAt()).isNotNull();
    }

    // --- helpers ---

    private Project saveProject(String name) {
        return projectRepository.save(Project.builder()
                .name(name)
                .type(ProjectType.CODE)
                .path("/tmp/" + name.toLowerCase().replace(' ', '-'))
                .status(ProjectStatus.STOPPED)
                .build());
    }

    private Workflow saveWorkflow(String name) {
        Project project = saveProject(name + " Project");
        return workflowRepository.save(Workflow.builder()
                .name(name)
                .project(project)
                .build());
    }

    private WorkflowVersion saveVersion(String workflowId, int versionNumber) {
        return saveVersion(workflowId, versionNumber, "user", "change " + versionNumber);
    }

    private WorkflowVersion saveVersion(String workflowId, int versionNumber, String changeSource, String changeMessage) {
        WorkflowVersion version = WorkflowVersion.builder()
                .workflowId(workflowId)
                .versionNumber(versionNumber)
                .snapshot("{\"blocks\":[]}")
                .changeSource(changeSource)
                .changeMessage(changeMessage)
                .build();
        return repository.saveAndFlush(version);
    }
}
