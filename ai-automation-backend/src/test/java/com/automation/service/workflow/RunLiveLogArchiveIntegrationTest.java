package com.automation.service.workflow;

import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.entity.ChangeSource;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunLiveLog;
import com.automation.entity.RunStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.ProjectRepository;
import com.automation.repository.RunLiveLogRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.live.LiveLineKind;
import com.automation.service.live.LiveOutputSink;
import com.automation.service.workflow.RunLiveLogService.LiveLine;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against real SQLite: the gzip BLOB survives the round trip, the workflow's choice is read from its
 * column, deleting the run removes the log (FK cascade) and the choice is writable on a sealed workflow.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class RunLiveLogArchiveIntegrationTest {

    @Autowired private RunLiveLogArchive archive;
    @Autowired private RunLiveLogService liveLog;
    @Autowired private RunLiveLogRepository repository;
    @Autowired private RunDeletionService runDeletionService;
    @Autowired private WorkflowService workflowService;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private EntityManager entityManager;

    private Workflow workflow;

    @BeforeEach
    void setUp() {
        Project project = projectRepository.save(Project.builder()
                .name("Live Log Test").type(ProjectType.CODE)
                .path("/tmp/live-log-test").status(ProjectStatus.RUNNING).build());
        workflow = workflowRepository.save(Workflow.builder().name("Live Log Workflow").project(project).build());
    }

    private WorkflowRun finishedRun() {
        WorkflowRun run = runRepository.save(WorkflowRun.builder()
                .workflow(workflow).workflowName(workflow.getName())
                .status(RunStatus.COMPLETED).startedAt(Instant.now()).build());
        flush();
        return run;
    }

    private void emit(String runId, String... texts) {
        LiveOutputSink sink = liveLog.sinkFor(runId, 0, "Build");
        for (String text : texts) sink.emit(LiveLineKind.LINE, text);
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void storedLogSurvivesTheRoundTripAndGoesWithItsRun() {
        WorkflowRun run = finishedRun();
        emit(run.getId(), "compiling", "BUILD SUCCESS");

        archive.store(run.getId(), workflow.getId());
        flush();

        RunLiveLog row = repository.findById(run.getId()).orElseThrow();
        assertThat(row.getLineCount()).isEqualTo(2);
        assertThat(RunLiveLogCodec.decode(row.getContent()).lines())
                .extracting(LiveLine::text).containsExactly("compiling", "BUILD SUCCESS");

        runDeletionService.delete(run.getId());
        flush();

        assertThat(repository.findById(run.getId())).isEmpty();
    }

    @Test
    void theChoiceIsWritableOnASealedWorkflowAndOffKeepsNothing() {
        workflow.setSealed(true);
        workflowRepository.save(workflow);
        flush();

        workflowService.update(workflow.getId(), new UpdateWorkflowRequest(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, "off"), ChangeSource.USER);
        flush();
        assertThat(workflowRepository.findLiveLogPersistById(workflow.getId())).contains("off");

        WorkflowRun run = finishedRun();
        emit(run.getId(), "secret-ish output");
        archive.store(run.getId(), workflow.getId());
        flush();

        assertThat(repository.findById(run.getId())).isEmpty();
    }
}
