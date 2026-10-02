package com.automation.integration;

import com.automation.entity.Workflow;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowVersionRepository;
import com.automation.service.example.ExampleProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The example project on an instance without the dashboard-report module (TODO c04ffb86): the report
 * example would only fail there, so it is left out; the other examples come as usual.
 */
@SpringBootTest
@ActiveProfiles("integration")
@TestPropertySource(properties = "automation.modules.dashboard-report=false")
class ExampleProjectModuleOffIntegrationTest {

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
            versionRepository.deleteAll(versionRepository
                    .findByWorkflowIdOrderByVersionNumberDesc(workflow.getId(), Pageable.unpaged()).getContent());
        }
        workflowRepository.deleteAll(workflows);
        projectRepository.deleteById(projectId);
        projectId = null;
    }

    @Test
    void theReportExampleIsLeftOut_theOthersCome() {
        ExampleProjectService.Result result = exampleProjectService.ensure("en");
        projectId = result.project().getId();

        assertThat(workflowRepository.findByProjectId(projectId)).extracting(Workflow::getTemplateId)
                .containsExactlyInAnyOrder("blueprint-approval", "blueprint-if", "blueprint-loop", "blueprint-todo");
        assertThat(result.workflowsAdded()).isEqualTo(4);
    }
}
