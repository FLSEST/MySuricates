package com.automation.repository;

import com.automation.entity.NotificationConfig;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.Workflow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class NotificationConfigRepositoryTest {

    @Autowired
    private NotificationConfigRepository repository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private ProjectRepository projectRepository;

    private NotificationConfig globalConfig(boolean enabled) {
        return NotificationConfig.builder()
                .name("Global Config")
                .enabled(enabled)
                .workflowId(null)
                .build();
    }

    private NotificationConfig workflowConfig(String workflowId, boolean enabled) {
        return NotificationConfig.builder()
                .name("Workflow Config for " + workflowId)
                .enabled(enabled)
                .workflowId(workflowId)
                .build();
    }

    private String createWorkflow(String name) {
        Project project = projectRepository.save(Project.builder()
                .name("Project " + name)
                .type(ProjectType.CODE)
                .path("/tmp/notif-test-" + name.toLowerCase().replace(' ', '-'))
                .status(ProjectStatus.RUNNING)
                .build());
        Workflow workflow = workflowRepository.save(Workflow.builder()
                .project(project)
                .name(name)
                .description("Notification test workflow")
                .build());
        return workflow.getId();
    }

    // --- findByWorkflowId ---

    @Test
    void findByWorkflowId_returnsOnlyConfigsForGivenWorkflow() {
        String wfId = createWorkflow("WF-001");
        String otherId = createWorkflow("WF-Other");
        repository.save(workflowConfig(wfId, true));
        repository.save(workflowConfig(wfId, false));
        repository.save(workflowConfig(otherId, true));
        repository.save(globalConfig(true));

        List<NotificationConfig> result = repository.findByWorkflowId(wfId);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(c -> wfId.equals(c.getWorkflowId()));
    }

    @Test
    void findByWorkflowId_returnsEmptyForUnknownWorkflow() {
        String existingId = createWorkflow("WF-Existing");
        repository.save(workflowConfig(existingId, true));

        List<NotificationConfig> result = repository.findByWorkflowId("wf-nonexistent");

        assertThat(result).isEmpty();
    }

    @Test
    void findByWorkflowId_doesNotReturnGlobalConfigs() {
        repository.save(globalConfig(true));

        List<NotificationConfig> result = repository.findByWorkflowId("wf-any");

        assertThat(result).isEmpty();
    }

    // --- findByWorkflowIdIsNull ---

    @Test
    void findByWorkflowIdIsNull_returnsOnlyGlobalConfigs() {
        String specificId = createWorkflow("WF-Specific");
        repository.save(globalConfig(true));
        repository.save(globalConfig(false));
        repository.save(workflowConfig(specificId, true));

        List<NotificationConfig> result = repository.findByWorkflowIdIsNull();

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(c -> c.getWorkflowId() == null);
    }

    @Test
    void findByWorkflowIdIsNull_returnsEmptyWhenNoGlobalConfigs() {
        String wfId = createWorkflow("WF-001");
        repository.save(workflowConfig(wfId, true));

        List<NotificationConfig> result = repository.findByWorkflowIdIsNull();

        assertThat(result).isEmpty();
    }

    // --- findEnabledForWorkflow ---

    @Test
    void findEnabledForWorkflow_returnsEnabledGlobalAndWorkflowSpecific() {
        String wfId = createWorkflow("WF-Target");
        repository.save(globalConfig(true));           // should match (global + enabled)
        repository.save(workflowConfig(wfId, true));   // should match (specific + enabled)

        List<NotificationConfig> result = repository.findEnabledForWorkflow(wfId);

        assertThat(result).hasSize(2);
    }

    @Test
    void findEnabledForWorkflow_excludesDisabledConfigs() {
        String wfId = createWorkflow("WF-Target");
        repository.save(globalConfig(false));           // excluded (disabled)
        repository.save(workflowConfig(wfId, false));   // excluded (disabled)
        repository.save(workflowConfig(wfId, true));    // included

        List<NotificationConfig> result = repository.findEnabledForWorkflow(wfId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isEnabled()).isTrue();
    }

    @Test
    void findEnabledForWorkflow_excludesConfigsForOtherWorkflows() {
        String wfId = createWorkflow("WF-Target");
        String otherId = createWorkflow("WF-Other");
        repository.save(workflowConfig(otherId, true)); // excluded (different workflow)
        repository.save(workflowConfig(wfId, true));        // included

        List<NotificationConfig> result = repository.findEnabledForWorkflow(wfId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getWorkflowId()).isEqualTo(wfId);
    }

    @Test
    void findEnabledForWorkflow_returnsEmptyWhenNoneMatch() {
        String otherId = createWorkflow("WF-Other");
        repository.save(globalConfig(false));                  // disabled
        repository.save(workflowConfig(otherId, true));     // different workflow

        List<NotificationConfig> result = repository.findEnabledForWorkflow("wf-target");

        assertThat(result).isEmpty();
    }

    @Test
    void findEnabledForWorkflow_includesOnlyEnabledGlobalWhenNoSpecificExists() {
        repository.save(globalConfig(true));
        repository.save(globalConfig(false));

        List<NotificationConfig> result = repository.findEnabledForWorkflow("wf-any");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getWorkflowId()).isNull();
        assertThat(result.get(0).isEnabled()).isTrue();
    }

    // --- Standard JPA repository methods ---

    @Test
    void save_persistsAndGeneratesId() {
        NotificationConfig config = NotificationConfig.builder()
                .name("Test Config")
                .enabled(true)
                .webhookUrl("https://hooks.example.com/test")
                .build();

        NotificationConfig saved = repository.save(config);

        assertThat(saved.getId()).isNotNull().isNotBlank();
        assertThat(saved.getName()).isEqualTo("Test Config");
        assertThat(saved.getWebhookUrl()).isEqualTo("https://hooks.example.com/test");
    }

    @Test
    void findById_returnsPersistedConfig() {
        String wfId = createWorkflow("WF-Find");
        NotificationConfig saved = repository.save(NotificationConfig.builder()
                .name("Findable Config")
                .enabled(true)
                .workflowId(wfId)
                .build());

        var found = repository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Findable Config");
        assertThat(found.get().getWorkflowId()).isEqualTo(wfId);
    }

    @Test
    void delete_removesConfig() {
        NotificationConfig saved = repository.save(globalConfig(true));
        String id = saved.getId();

        repository.delete(saved);
        repository.flush();

        assertThat(repository.findById(id)).isEmpty();
    }

    // --- Builder defaults ---

    @Test
    void builder_appliesDefaults() {
        NotificationConfig config = NotificationConfig.builder()
                .name("Defaults Test")
                .build();

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.isTriggerOnSuccess()).isTrue();
        assertThat(config.isTriggerOnFailure()).isTrue();
        assertThat(config.isTriggerAlways()).isFalse();
        assertThat(config.getChannelType()).isEqualTo(NotificationConfig.ChannelType.WEBHOOK);
    }
}
