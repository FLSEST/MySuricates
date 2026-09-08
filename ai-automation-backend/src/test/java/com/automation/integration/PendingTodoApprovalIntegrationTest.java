package com.automation.integration;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.Todo;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.event.TodoCreatedEvent;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.todo.PendingTodoApprovalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Approving pending todos must run inside a transaction: the {@link TodoCreatedEvent} is
 * consumed by the TodoTrigger with {@code @TransactionalEventListener(AFTER_COMMIT)} — without an
 * active transaction Spring drops that event silently and no trigger ever fires. Deliberately
 * <b>not</b> {@code @Transactional} on the test, so the service's own transaction (or its
 * absence) is what the probe observes (Audit-TODO 9b4845ab).
 */
@SpringBootTest
@ActiveProfiles("integration")
@Import(PendingTodoApprovalIntegrationTest.AfterCommitProbe.class)
class PendingTodoApprovalIntegrationTest {

    @TestConfiguration
    static class AfterCommitProbe {
        final List<String> receivedTodoIds = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onTodoCreated(TodoCreatedEvent event) {
            receivedTodoIds.add(event.getTodo().getId());
        }
    }

    @Autowired private PendingTodoApprovalService approvalService;
    @Autowired private AfterCommitProbe probe;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private TodoRepository todoRepository;

    private final List<Runnable> cleanup = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            try { cleanup.get(i).run(); } catch (Exception ignored) { /* already gone */ }
        }
        cleanup.clear();
        probe.receivedTodoIds.clear();
    }

    @Test
    void approvePendingTodos_publishesTodoCreatedEvent_afterCommit() {
        long nano = System.nanoTime();
        Project project = projectRepository.save(Project.builder()
                .name("approve-it-" + nano).type(ProjectType.CODE)
                .path("/tmp/approve-it-" + nano).status(ProjectStatus.STOPPED).build());
        cleanup.add(() -> projectRepository.deleteById(project.getId()));
        Workflow workflow = workflowRepository.save(Workflow.builder()
                .name("approve-wf-" + nano).project(project).blocks(new ArrayList<>()).build());
        cleanup.add(() -> workflowRepository.deleteById(workflow.getId()));

        Map<String, Object> pending = new HashMap<>();
        pending.put("title", "Approved IT todo " + nano);
        pending.put("priority", "HIGH");
        pending.put("description", "from pending");
        pending.put("projectId", project.getId());
        pending.put("category", "it-" + nano);
        pending.put("tags", "[]");
        Map<String, Object> meta = new HashMap<>();
        meta.put("pending_todos", List.of(pending));
        WorkflowRun run = runRepository.save(WorkflowRun.builder()
                .workflow(workflow).workflowName(workflow.getName())
                .status(RunStatus.COMPLETED).metadata(meta).build());
        cleanup.add(() -> runRepository.deleteById(run.getId()));

        approvalService.approvePendingTodos(run.getId(), true);

        List<Todo> created = todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                project.getId(), Todo.Source.WORKFLOW, "workflow_run", workflow.getId(), Todo.Status.DONE);
        created.forEach(t -> cleanup.add(() -> todoRepository.deleteById(t.getId())));
        assertThat(created).extracting(Todo::getTitle).containsExactly("Approved IT todo " + nano);
        assertThat(probe.receivedTodoIds).containsExactly(created.get(0).getId());
        assertThat(runRepository.findById(run.getId()).orElseThrow().getMetadata()).doesNotContainKey("pending_todos");
    }
}
