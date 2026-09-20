package com.automation.repository;

import com.automation.entity.*;
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
class ScheduledWorkflowRepositoryTest {

    @Autowired
    private ScheduledWorkflowRepository repository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private WorkflowChainRepository chainRepository;

    private Project saveProject(String name) {
        return projectRepository.save(Project.builder()
                .name(name)
                .type(ProjectType.CODE)
                .path("/tmp/" + name.toLowerCase().replace(' ', '-'))
                .status(ProjectStatus.RUNNING)
                .build());
    }

    private Workflow saveWorkflow(String name, Project project) {
        return workflowRepository.save(Workflow.builder()
                .name(name)
                .project(project)
                .build());
    }

    private WorkflowChain saveChain(String name) {
        return chainRepository.save(WorkflowChain.builder()
                .name(name)
                .build());
    }

    private ScheduledWorkflow schedule(Workflow workflow, boolean enabled, Instant nextRunAt) {
        return ScheduledWorkflow.builder()
                .workflow(workflow)
                .targetType(ScheduledWorkflow.TargetType.WORKFLOW)
                .cronExpression("0 * * * *")
                .enabled(enabled)
                .nextRunAt(nextRunAt)
                .build();
    }

    private ScheduledWorkflow chainSchedule(WorkflowChain chain, boolean enabled, Instant nextRunAt) {
        return ScheduledWorkflow.builder()
                .chain(chain)
                .targetType(ScheduledWorkflow.TargetType.CHAIN)
                .cronExpression("0 * * * *")
                .enabled(enabled)
                .nextRunAt(nextRunAt)
                .build();
    }

    // --- findByEnabledTrue ---

    @Test
    void findByEnabledTrue_returnsOnlyEnabledSchedules() {
        Project project = saveProject("Project A");
        Workflow workflow = saveWorkflow("Workflow A", project);
        repository.save(schedule(workflow, true, Instant.now()));
        repository.save(schedule(workflow, true, Instant.now()));
        repository.save(schedule(workflow, false, Instant.now()));

        List<ScheduledWorkflow> result = repository.findByEnabledTrue();

        assertThat(result).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result).allMatch(ScheduledWorkflow::isEnabled);
    }

    @Test
    void findByEnabledTrue_emptyWhenAllDisabled() {
        Project project = saveProject("Project B");
        Workflow workflow = saveWorkflow("Workflow B", project);
        repository.save(schedule(workflow, false, Instant.now()));

        List<ScheduledWorkflow> result = repository.findByEnabledTrue();

        assertThat(result).isEmpty();
    }

    @Test
    void findByEnabledTrue_emptyWhenNoSchedulesExist() {
        List<ScheduledWorkflow> result = repository.findByEnabledTrue();

        assertThat(result).isEmpty();
    }

    // --- findDueSchedules ---

    @Test
    void findDueSchedules_returnsDueEnabledSchedules() {
        Project project = saveProject("Project C");
        Workflow workflow = saveWorkflow("Workflow C", project);
        Instant past = Instant.now().minusSeconds(60);
        Instant future = Instant.now().plusSeconds(3600);
        repository.save(schedule(workflow, true, past));
        repository.save(schedule(workflow, true, future));

        List<ScheduledWorkflow> result = repository.findDueSchedules(Instant.now());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getNextRunAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void findDueSchedules_excludesDisabledSchedules() {
        Project project = saveProject("Project D");
        Workflow workflow = saveWorkflow("Workflow D", project);
        Instant past = Instant.now().minusSeconds(60);
        repository.save(schedule(workflow, false, past));

        List<ScheduledWorkflow> result = repository.findDueSchedules(Instant.now());

        assertThat(result).isEmpty();
    }

    @Test
    void findDueSchedules_emptyWhenNothingDue() {
        Project project = saveProject("Project E");
        Workflow workflow = saveWorkflow("Workflow E", project);
        Instant future = Instant.now().plusSeconds(3600);
        repository.save(schedule(workflow, true, future));

        List<ScheduledWorkflow> result = repository.findDueSchedules(Instant.now());

        assertThat(result).isEmpty();
    }

    // --- findByIdWithWorkflowSkills ---

    @Test
    void findByIdWithWorkflowSkills_returnsScheduleWithWorkflow() {
        Project project = saveProject("Project F");
        Workflow workflow = saveWorkflow("Workflow F", project);
        ScheduledWorkflow saved = repository.save(schedule(workflow, true, Instant.now()));

        Optional<ScheduledWorkflow> result = repository.findByIdWithWorkflowSkills(saved.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(saved.getId());
        assertThat(result.get().getWorkflow()).isNotNull();
        assertThat(result.get().getWorkflow().getName()).isEqualTo("Workflow F");
    }

    @Test
    void findByIdWithWorkflowSkills_returnsEmptyForUnknownId() {
        Optional<ScheduledWorkflow> result = repository.findByIdWithWorkflowSkills("no-such-id");

        assertThat(result).isEmpty();
    }

    @Test
    void findByIdWithWorkflowSkills_activatedSkillsIsAccessible() {
        Project project = saveProject("Project G");
        Workflow workflow = saveWorkflow("Workflow G", project);
        ScheduledWorkflow saved = repository.save(schedule(workflow, true, Instant.now()));

        Optional<ScheduledWorkflow> result = repository.findByIdWithWorkflowSkills(saved.getId());

        assertThat(result).isPresent();
        // activatedSkills must be eagerly loaded (no LazyInitializationException)
        assertThat(result.get().getWorkflow().getActivatedSkills()).isNotNull();
    }

    // --- findByWorkflowId ---

    @Test
    void findByWorkflowId_returnsSchedulesForWorkflow() {
        Project project = saveProject("Project H");
        Workflow workflow = saveWorkflow("Workflow H", project);
        Workflow other = saveWorkflow("Other Workflow", project);
        repository.save(schedule(workflow, true, Instant.now()));
        repository.save(schedule(workflow, false, Instant.now()));
        repository.save(schedule(other, true, Instant.now()));

        List<ScheduledWorkflow> result = repository.findByWorkflowId(workflow.getId());

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(s -> workflow.getId().equals(s.getWorkflow().getId()));
    }

    @Test
    void findByWorkflowId_emptyForUnknownWorkflow() {
        List<ScheduledWorkflow> result = repository.findByWorkflowId("no-such-workflow");

        assertThat(result).isEmpty();
    }

    @Test
    void findByWorkflowId_emptyWhenNoSchedulesForWorkflow() {
        Project project = saveProject("Project I");
        Workflow workflow = saveWorkflow("Workflow I", project);

        List<ScheduledWorkflow> result = repository.findByWorkflowId(workflow.getId());

        assertThat(result).isEmpty();
    }

    // --- findByChainId ---

    @Test
    void findByChainId_returnsSchedulesForChain() {
        WorkflowChain chain = saveChain("Chain Alpha");
        WorkflowChain other = saveChain("Chain Beta");
        repository.save(chainSchedule(chain, true, Instant.now()));
        repository.save(chainSchedule(chain, false, Instant.now()));
        repository.save(chainSchedule(other, true, Instant.now()));

        List<ScheduledWorkflow> result = repository.findByChainId(chain.getId());

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(s -> chain.getId().equals(s.getChain().getId()));
    }

    @Test
    void findByChainId_emptyForUnknownChain() {
        List<ScheduledWorkflow> result = repository.findByChainId("no-such-chain");

        assertThat(result).isEmpty();
    }

    @Test
    void findByChainId_emptyWhenNoSchedulesForChain() {
        WorkflowChain chain = saveChain("Chain Gamma");

        List<ScheduledWorkflow> result = repository.findByChainId(chain.getId());

        assertThat(result).isEmpty();
    }

    // --- findSchedulesInRange ---

    @Test
    void findSchedulesInRange_returnsEnabledSchedulesWithinRange() {
        Project project = saveProject("Project J");
        Workflow workflow = saveWorkflow("Workflow J", project);
        Instant from = Instant.now().minusSeconds(3600);
        Instant to = Instant.now().plusSeconds(3600);
        Instant inRange = Instant.now();
        Instant before = from.minusSeconds(60);
        Instant after = to.plusSeconds(60);

        repository.save(schedule(workflow, true, inRange));
        repository.save(schedule(workflow, true, before));
        repository.save(schedule(workflow, true, after));

        List<ScheduledWorkflow> result = repository.findSchedulesInRange(from, to);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getNextRunAt()).isBetween(from, to);
    }

    @Test
    void findSchedulesInRange_excludesDisabledSchedules() {
        Project project = saveProject("Project K");
        Workflow workflow = saveWorkflow("Workflow K", project);
        Instant from = Instant.now().minusSeconds(3600);
        Instant to = Instant.now().plusSeconds(3600);
        repository.save(schedule(workflow, false, Instant.now()));

        List<ScheduledWorkflow> result = repository.findSchedulesInRange(from, to);

        assertThat(result).isEmpty();
    }

    @Test
    void findSchedulesInRange_emptyWhenNoneInRange() {
        Project project = saveProject("Project L");
        Workflow workflow = saveWorkflow("Workflow L", project);
        Instant from = Instant.now().plusSeconds(7200);
        Instant to = Instant.now().plusSeconds(10800);
        repository.save(schedule(workflow, true, Instant.now()));

        List<ScheduledWorkflow> result = repository.findSchedulesInRange(from, to);

        assertThat(result).isEmpty();
    }

    @Test
    void findSchedulesInRange_includesBoundaryValues() {
        Project project = saveProject("Project M");
        Workflow workflow = saveWorkflow("Workflow M", project);
        Instant from = Instant.now().minusSeconds(60);
        Instant to = Instant.now().plusSeconds(60);

        ScheduledWorkflow atFrom = repository.save(schedule(workflow, true, from));
        ScheduledWorkflow atTo = repository.save(schedule(workflow, true, to));

        List<ScheduledWorkflow> result = repository.findSchedulesInRange(from, to);

        assertThat(result).extracting(ScheduledWorkflow::getId)
                .contains(atFrom.getId(), atTo.getId());
    }

    // --- findAllWithTargets ---

    /**
     * Der Join darf nichts verschlucken: Ein Zeitplan ohne Workflow (Ketten-Ziel) und einer ohne
     * Kette (Workflow-Ziel) muessen beide in der Liste stehen — {@code LEFT}, nicht {@code INNER}.
     */
    @Test
    void findAllWithTargets_returnsWorkflowAndChainSchedules() {
        Project project = saveProject("Project N");
        Workflow workflow = saveWorkflow("Workflow N", project);
        WorkflowChain chain = saveChain("Chain N");

        ScheduledWorkflow forWorkflow = repository.save(schedule(workflow, true, Instant.now()));
        ScheduledWorkflow forChain = repository.save(chainSchedule(chain, true, Instant.now()));

        List<ScheduledWorkflow> result = repository.findAllWithTargets();

        assertThat(result).extracting(ScheduledWorkflow::getId)
                .contains(forWorkflow.getId(), forChain.getId());
    }

    /** Die Ziele kommen mitgeholt zurueck, sonst waere der Join sinnlos. */
    @Test
    void findAllWithTargets_fetchesWorkflowProjectAndChain() {
        Project project = saveProject("Project O");
        Workflow workflow = saveWorkflow("Workflow O", project);
        WorkflowChain chain = saveChain("Chain O");
        repository.save(schedule(workflow, true, Instant.now()));
        repository.save(chainSchedule(chain, true, Instant.now()));

        List<ScheduledWorkflow> result = repository.findAllWithTargets();

        assertThat(result)
                .filteredOn(s -> s.getWorkflow() != null && s.getWorkflow().getId().equals(workflow.getId()))
                .allSatisfy(s -> {
                    assertThat(s.getWorkflow().getName()).isEqualTo("Workflow O");
                    assertThat(s.getWorkflow().getProject().getName()).isEqualTo("Project O");
                });
        assertThat(result)
                .filteredOn(s -> s.getChain() != null && s.getChain().getId().equals(chain.getId()))
                .allSatisfy(s -> assertThat(s.getChain().getName()).isEqualTo("Chain O"));
    }

    /** Ein Zeitplan darf nicht doppelt erscheinen, nur weil mehrere Assoziationen gejoint werden. */
    @Test
    void findAllWithTargets_returnsEachScheduleOnce() {
        Project project = saveProject("Project P");
        Workflow workflow = saveWorkflow("Workflow P", project);
        ScheduledWorkflow saved = repository.save(schedule(workflow, true, Instant.now()));

        List<ScheduledWorkflow> result = repository.findAllWithTargets();

        assertThat(result).extracting(ScheduledWorkflow::getId)
                .filteredOn(id -> id.equals(saved.getId()))
                .hasSize(1);
    }
}
