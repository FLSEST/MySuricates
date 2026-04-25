package com.automation.repository;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.TodoTrigger;
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
class TodoTriggerRepositoryTest {

    @Autowired
    private TodoTriggerRepository repository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    void findByEnabledTrue_returnsOnlyEnabledTriggers() {
        Workflow workflow = saveWorkflow("Enabled Filter");
        TodoTrigger enabled = saveTrigger(workflow, "tag-enabled", true);
        TodoTrigger disabled = saveTrigger(workflow, "tag-disabled", false);

        List<TodoTrigger> result = repository.findByEnabledTrue();

        assertThat(result).extracting(TodoTrigger::getId).contains(enabled.getId());
        assertThat(result).extracting(TodoTrigger::getId).doesNotContain(disabled.getId());
        assertThat(result).allMatch(TodoTrigger::isEnabled);
    }

    @Test
    void findByTargetWorkflowId_returnsOnlyTriggersForMatchingWorkflow() {
        Workflow workflow1 = saveWorkflow("Target WF1");
        Workflow workflow2 = saveWorkflow("Target WF2");
        TodoTrigger trigger1 = saveTrigger(workflow1, "tag-alpha", true);
        TodoTrigger trigger2 = saveTrigger(workflow1, "tag-beta", true);
        TodoTrigger triggerOther = saveTrigger(workflow2, "tag-other", true);

        List<TodoTrigger> result = repository.findByTargetWorkflowId(workflow1.getId());

        assertThat(result).extracting(TodoTrigger::getId)
                .containsExactlyInAnyOrder(trigger1.getId(), trigger2.getId())
                .doesNotContain(triggerOther.getId());
    }

    @Test
    void findByTargetWorkflowId_returnsEmptyWhenNoTriggersExist() {
        Workflow workflow = saveWorkflow("Empty WF");

        List<TodoTrigger> result = repository.findByTargetWorkflowId(workflow.getId());

        assertThat(result).isEmpty();
    }

    @Test
    void findByTargetWorkflowId_returnsEmptyForUnknownId() {
        List<TodoTrigger> result = repository.findByTargetWorkflowId("no-such-id");

        assertThat(result).isEmpty();
    }

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

    private TodoTrigger saveTrigger(Workflow workflow, String tagPattern, boolean enabled) {
        return repository.save(TodoTrigger.builder()
                .targetWorkflow(workflow)
                .tagPattern(tagPattern)
                .enabled(enabled)
                .build());
    }
}
