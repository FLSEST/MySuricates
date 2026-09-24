package com.automation.integration;

import com.automation.config.WorkflowTemplateSeeder;
import com.automation.entity.BlockDefinition;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.TemplateVerification;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowTemplate;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TemplateVerificationRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowTemplateRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A built-in whose file no longer ships leaves every instance at the next start (TODO 7ddacd1d) —
 * against the real schema, because what hangs on the row is the database's job: its verifications
 * go ({@code ON DELETE CASCADE}), a workflow made from it stays and only loses the back-reference
 * ({@code ON DELETE SET NULL}), and a user template is never touched.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class RetiredBuiltInTemplateIntegrationTest {

    @Autowired private WorkflowTemplateSeeder seeder;
    @Autowired private WorkflowTemplateRepository templateRepository;
    @Autowired private TemplateVerificationRepository verificationRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void aRetiredBuiltInGoesWithItsVerifications_whileWorkflowsAndUserTemplatesStay() {
        Project project = projectRepository.save(Project.builder()
                .name("Retired-Template-" + System.nanoTime()).type(ProjectType.CODE)
                .path("/tmp/retired-" + System.nanoTime()).status(ProjectStatus.STOPPED).build());
        WorkflowTemplate retired = templateRepository.save(template("retired-" + System.nanoTime(), true));
        WorkflowTemplate userTemplate = templateRepository.save(template("user-" + System.nanoTime(), false));
        verificationRepository.save(TemplateVerification.builder().templateId(retired.getId())
                .projectId(project.getId()).verifiedAt(Instant.now()).build());
        Workflow fromRetired = workflowRepository.save(Workflow.builder().name("From retired " + System.nanoTime())
                .project(project).templateId(retired.getId())
                .blocks(List.of(BlockDefinition.builder().type("shell").config(Map.of("command", "date")).build()))
                .build());
        entityManager.flush();

        seeder.run(null);
        entityManager.flush();
        entityManager.clear();

        assertThat(templateRepository.findById(retired.getId())).isEmpty();
        assertThat(verificationRepository.findByTemplateId(retired.getId())).isEmpty();
        assertThat(workflowRepository.findById(fromRetired.getId())).hasValueSatisfying(
                workflow -> assertThat(workflow.getTemplateId()).isNull());
        assertThat(templateRepository.findById(userTemplate.getId())).isPresent();
        assertThat(templateRepository.findById("code-quality")).as("shipped built-ins stay").isPresent();
    }

    private static WorkflowTemplate template(String id, boolean builtIn) {
        return WorkflowTemplate.builder()
                .id(id)
                .name(Map.of("de", "Vorlage " + id, "en", "Template " + id))
                .blocks(List.of(BlockDefinition.builder().type("shell").config(Map.of("command", "ls")).build()))
                .builtIn(builtIn)
                .build();
    }
}
