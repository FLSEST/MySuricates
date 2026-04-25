package com.automation.repository;

import com.automation.entity.*;
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
class CompletionTriggerRepositoryTest {

    @Autowired
    private CompletionTriggerRepository repository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private ProjectRepository projectRepository;

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

    private CompletionTrigger saveTrigger(Workflow source, Workflow target, boolean enabled) {
        return repository.save(CompletionTrigger.builder()
                .sourceWorkflow(source)
                .targetWorkflow(target)
                .enabled(enabled)
                .build());
    }

    // --- findBySourceWorkflowId ---

    @Test
    void findBySourceWorkflowId_returnsOnlyMatching() {
        Workflow source = saveWorkflow("Source A");
        Workflow target1 = saveWorkflow("Target A1");
        Workflow target2 = saveWorkflow("Target A2");
        Workflow otherSource = saveWorkflow("Other Source");
        Workflow otherTarget = saveWorkflow("Other Target");

        saveTrigger(source, target1, true);
        saveTrigger(source, target2, true);
        saveTrigger(otherSource, otherTarget, true);

        List<CompletionTrigger> result = repository.findBySourceWorkflowId(source.getId());

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(t -> t.getSourceWorkflow().getId().equals(source.getId()));
    }

    @Test
    void findBySourceWorkflowId_returnsEmptyForUnknownId() {
        List<CompletionTrigger> result = repository.findBySourceWorkflowId("no-such-id");

        assertThat(result).isEmpty();
    }

    // --- findByTargetWorkflowId ---

    @Test
    void findByTargetWorkflowId_returnsOnlyMatching() {
        Workflow source1 = saveWorkflow("Source B1");
        Workflow source2 = saveWorkflow("Source B2");
        Workflow target = saveWorkflow("Target B");
        Workflow otherSource = saveWorkflow("Other Source B");
        Workflow otherTarget = saveWorkflow("Other Target B");

        saveTrigger(source1, target, true);
        saveTrigger(source2, target, true);
        saveTrigger(otherSource, otherTarget, true);

        List<CompletionTrigger> result = repository.findByTargetWorkflowId(target.getId());

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(t -> t.getTargetWorkflow().getId().equals(target.getId()));
    }

    @Test
    void findByTargetWorkflowId_returnsEmptyForUnknownId() {
        List<CompletionTrigger> result = repository.findByTargetWorkflowId("no-such-id");

        assertThat(result).isEmpty();
    }

    // --- findBySourceWorkflowIdAndEnabledTrue ---

    @Test
    void findBySourceWorkflowIdAndEnabledTrue_excludesDisabledTriggers() {
        Workflow source = saveWorkflow("Source C");
        Workflow target1 = saveWorkflow("Target C1");
        Workflow target2 = saveWorkflow("Target C2");

        saveTrigger(source, target1, true);
        saveTrigger(source, target2, false);

        List<CompletionTrigger> result = repository.findBySourceWorkflowIdAndEnabledTrue(source.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTargetWorkflow().getId()).isEqualTo(target1.getId());
    }

    @Test
    void findBySourceWorkflowIdAndEnabledTrue_returnsEmptyWhenAllDisabled() {
        Workflow source = saveWorkflow("Source D");
        Workflow target = saveWorkflow("Target D");

        saveTrigger(source, target, false);

        List<CompletionTrigger> result = repository.findBySourceWorkflowIdAndEnabledTrue(source.getId());

        assertThat(result).isEmpty();
    }

    // --- existsBySourceWorkflowIdAndTargetWorkflowId ---

    @Test
    void existsBySourceWorkflowIdAndTargetWorkflowId_trueWhenPairExists() {
        Workflow source = saveWorkflow("Source E");
        Workflow target = saveWorkflow("Target E");

        saveTrigger(source, target, true);

        boolean exists = repository.existsBySourceWorkflowIdAndTargetWorkflowId(source.getId(), target.getId());

        assertThat(exists).isTrue();
    }

    @Test
    void existsBySourceWorkflowIdAndTargetWorkflowId_falseWhenTargetMismatches() {
        Workflow source = saveWorkflow("Source F");
        Workflow target = saveWorkflow("Target F");
        Workflow otherTarget = saveWorkflow("Other Target F");

        saveTrigger(source, target, true);

        boolean exists = repository.existsBySourceWorkflowIdAndTargetWorkflowId(source.getId(), otherTarget.getId());

        assertThat(exists).isFalse();
    }

    @Test
    void existsBySourceWorkflowIdAndTargetWorkflowId_falseWhenSourceUnknown() {
        Workflow target = saveWorkflow("Target G");

        boolean exists = repository.existsBySourceWorkflowIdAndTargetWorkflowId("no-such-source", target.getId());

        assertThat(exists).isFalse();
    }

    // --- countByEnabledTrue ---

    @Test
    void countByEnabledTrue_countsOnlyEnabled() {
        Workflow source = saveWorkflow("Source H");
        Workflow target1 = saveWorkflow("Target H1");
        Workflow target2 = saveWorkflow("Target H2");
        Workflow target3 = saveWorkflow("Target H3");

        long before = repository.countByEnabledTrue();

        saveTrigger(source, target1, true);
        saveTrigger(source, target2, true);
        saveTrigger(source, target3, false);

        long after = repository.countByEnabledTrue();

        assertThat(after - before).isEqualTo(2);
    }

    @Test
    void countByEnabledTrue_returnsZeroWhenAllDisabled() {
        Workflow source = saveWorkflow("Source I");
        Workflow target = saveWorkflow("Target I");

        long before = repository.countByEnabledTrue();
        saveTrigger(source, target, false);
        long after = repository.countByEnabledTrue();

        assertThat(after).isEqualTo(before);
    }
}
