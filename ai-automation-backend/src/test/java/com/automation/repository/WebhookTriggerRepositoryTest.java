package com.automation.repository;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.WebhookTrigger;
import com.automation.entity.Workflow;
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

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class WebhookTriggerRepositoryTest {

    @Autowired
    private WebhookTriggerRepository repository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findBySecretToken_returnsMatchingTrigger() {
        Workflow workflow = saveWorkflow("Webhook A");
        WebhookTrigger saved = saveTrigger(workflow, "secret-alpha", true);

        var result = repository.findBySecretToken("secret-alpha");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(saved.getId());
        assertThat(result.get().getWorkflow().getId()).isEqualTo(workflow.getId());
    }

    @Test
    void findByWorkflowId_returnsOnlyTriggersForMatchingWorkflow() {
        Workflow firstWorkflow = saveWorkflow("Webhook First");
        Workflow secondWorkflow = saveWorkflow("Webhook Second");

        WebhookTrigger first = saveTrigger(firstWorkflow, "secret-first", true);
        WebhookTrigger second = saveTrigger(firstWorkflow, "secret-second", false);
        saveTrigger(secondWorkflow, "secret-third", true);

        List<WebhookTrigger> result = repository.findByWorkflowId(firstWorkflow.getId());

        assertThat(result).extracting(WebhookTrigger::getId)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(result).allMatch(trigger -> trigger.getWorkflow().getId().equals(firstWorkflow.getId()));
    }

    @Test
    void countByEnabledTrue_countsOnlyEnabledTriggers() {
        Workflow workflow = saveWorkflow("Webhook Count");
        saveTrigger(workflow, "secret-enabled-1", true);
        saveTrigger(workflow, "secret-enabled-2", true);
        saveTrigger(workflow, "secret-disabled", false);

        long count = repository.countByEnabledTrue();

        assertThat(count).isEqualTo(2);
    }

    @Test
    void incrementTriggerCount_updatesCounterAndTimestamp() {
        Workflow workflow = saveWorkflow("Webhook Increment");
        WebhookTrigger saved = saveTrigger(workflow, "secret-increment", true);
        Instant timestamp = Instant.parse("2026-04-13T20:15:00Z");

        repository.incrementTriggerCount(saved.getId(), timestamp);
        repository.flush();
        entityManager.clear();

        WebhookTrigger updated = repository.findById(saved.getId()).orElseThrow();

        assertThat(updated.getTriggerCount()).isEqualTo(1);
        assertThat(updated.getLastTriggeredAt()).isEqualTo(timestamp);
    }

    private Workflow saveWorkflow(String name) {
        Project project = projectRepository.save(Project.builder()
                .name("Project " + name)
                .type(ProjectType.CODE)
                .path("/tmp/" + name.toLowerCase().replace(' ', '-'))
                .status(ProjectStatus.RUNNING)
                .build());

        return workflowRepository.save(Workflow.builder()
                .project(project)
                .name(name)
                .description("Repository test workflow")
                .build());
    }

    private WebhookTrigger saveTrigger(Workflow workflow, String secretToken, boolean enabled) {
        return repository.save(WebhookTrigger.builder()
                .workflow(workflow)
                .secretToken(secretToken)
                .enabled(enabled)
                .description("Test trigger")
                .defaultContext(Map.of("branch", "main"))
                .build());
    }
}
