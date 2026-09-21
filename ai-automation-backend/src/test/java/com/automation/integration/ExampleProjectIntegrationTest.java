package com.automation.integration;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Project;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowVersion;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowVersionRepository;
import com.automation.service.example.ExampleProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The example project against the real context (TODO d12c7560): the seeded built-in
 * {@code example-approval} template, {@code PathValidator} and the version row of the gateway —
 * none of which the unit test sees.
 */
@SpringBootTest
@ActiveProfiles("integration")
class ExampleProjectIntegrationTest {

    @TempDir
    static Path examples;

    @DynamicPropertySource
    static void examplesDirectory(DynamicPropertyRegistry registry) {
        registry.add("automation.examples.directory", () -> examples.toString());
    }

    @Autowired private ExampleProjectService exampleProjectService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowVersionRepository versionRepository;

    private String projectId;

    @AfterEach
    void cleanup() {
        if (projectId == null) {
            return;
        }
        List<Workflow> workflows = workflowRepository.findByProjectId(projectId);
        for (Workflow workflow : workflows) {
            versionRepository.deleteAll(versionRepository.findByWorkflowIdOrderByVersionNumberDesc(
                    workflow.getId(), org.springframework.data.domain.Pageable.unpaged()).getContent());
        }
        workflowRepository.deleteAll(workflows);
        projectRepository.deleteById(projectId);
    }

    @Test
    void createsTheProjectWithTheGermanApprovalExample_andReturnsItOnTheSecondCall() {
        ExampleProjectService.Result first = exampleProjectService.ensure("de");
        projectId = first.project().getId();

        assertThat(first.created()).isTrue();
        Project project = projectRepository.findById(projectId).orElseThrow();
        assertThat(project.getName()).isEqualTo("Beispielprojekt");
        assertThat(Path.of(project.getPath())).isEqualTo(examples.resolve("example-project"));
        assertThat(examples.resolve("example-project/README.md")).isRegularFile();

        List<Workflow> workflows = workflowRepository.findByProjectId(projectId);
        Workflow approval = workflows.stream()
                .filter(w -> "example-approval".equals(w.getTemplateId()))
                .findFirst().orElseThrow();
        assertThat(approval.getName()).isEqualTo("Beispiel: Freigabe mit Messung");
        assertThat(approval.getBlocks()).extracting(BlockDefinition::getName)
                .containsExactly("messen", "freigabe", "abschluss");
        WorkflowVersion version = versionRepository
                .findTopByWorkflowIdOrderByVersionNumberDesc(approval.getId()).orElseThrow();
        assertThat(version.getChangeSource()).isEqualTo("example-project");

        ExampleProjectService.Result second = exampleProjectService.ensure("de");

        assertThat(second.created()).isFalse();
        assertThat(second.project().getId()).isEqualTo(projectId);
        assertThat(second.workflowsAdded()).isZero();
        assertThat(workflowRepository.findByProjectId(projectId)).hasSameSizeAs(workflows);
    }
}
