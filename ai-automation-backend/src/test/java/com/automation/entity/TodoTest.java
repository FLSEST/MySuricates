package com.automation.entity;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TodoTest {

    /**
     * Folgebefund zu ab1ed481: Wird ein TODO DONE, meldet es das beim naechsten save/saveAll selbst
     * (Spring-Data-Domainereignis). So kann kein Pfad — REST, TodoTrigger-Auto-Close, verknuepfter
     * Workflow — vergessen, den Audit-Befund auf AWAITING_VERIFICATION zu setzen.
     */
    @Nested
    class CompletionEvent {

        @Test
        void aTransitionToDone_registersOneCompletionEvent() {
            Todo todo = Todo.builder().id("t-1").title("t").status(Todo.Status.IN_PROGRESS).build();

            todo.setStatus(Todo.Status.DONE);
            todo.setStatus(Todo.Status.DONE);

            assertThat(todo.domainEvents()).singleElement()
                    .isEqualTo(new Todo.Completed(todo));
        }

        @Test
        void otherTransitions_registerNothing_andPublicationClearsTheEvents() {
            Todo todo = Todo.builder().id("t-1").title("t").status(Todo.Status.DONE).build();

            todo.setStatus(Todo.Status.ARCHIVED);
            todo.setStatus(Todo.Status.OPEN);
            assertThat(todo.domainEvents()).isEmpty();

            todo.setStatus(Todo.Status.DONE);
            assertThat(todo.domainEvents()).hasSize(1);
            todo.clearDomainEvents();
            assertThat(todo.domainEvents()).isEmpty();
        }

        @Test
        void aTodoBuiltAsDone_registersNothing() {
            assertThat(Todo.builder().title("t").status(Todo.Status.DONE).build().domainEvents()).isEmpty();
        }
    }

    @Nested
    class BuilderDefaults {

        @Test
        void should_setStatusToOpen_when_builderUsedWithoutStatus() {
            Todo todo = Todo.builder().title("t").build();
            assertThat(todo.getStatus()).isEqualTo(Todo.Status.OPEN);
        }

        @Test
        void should_setPriorityToMedium_when_builderUsedWithoutPriority() {
            Todo todo = Todo.builder().title("t").build();
            assertThat(todo.getPriority()).isEqualTo(Todo.Priority.MEDIUM);
        }

        @Test
        void should_setSourceToUser_when_builderUsedWithoutSource() {
            Todo todo = Todo.builder().title("t").build();
            assertThat(todo.getSource()).isEqualTo(Todo.Source.USER);
        }

        @Test
        void should_setFixAttemptsToZero_when_builderUsedWithoutFixAttempts() {
            Todo todo = Todo.builder().title("t").build();
            assertThat(todo.getFixAttempts()).isZero();
        }

        @Test
        void should_leaveOptionalFieldsNull_when_notSetViaBuilder() {
            Todo todo = Todo.builder().title("t").build();
            assertThat(todo.getId()).isNull();
            assertThat(todo.getDescription()).isNull();
            assertThat(todo.getTags()).isNull();
            assertThat(todo.getProjectId()).isNull();
            assertThat(todo.getLinkedWorkflowId()).isNull();
            assertThat(todo.getSourceAgent()).isNull();
            assertThat(todo.getSessionId()).isNull();
            assertThat(todo.getWorkflowRunId()).isNull();
            assertThat(todo.getReferenceType()).isNull();
            assertThat(todo.getReferenceId()).isNull();
            assertThat(todo.getRelatedTodoId()).isNull();
            assertThat(todo.getDueDate()).isNull();
            assertThat(todo.getLastFixRunId()).isNull();
            assertThat(todo.getResolutionState()).isNull();
            assertThat(todo.getCreatedAt()).isNull();
            assertThat(todo.getUpdatedAt()).isNull();
        }
    }

    @Nested
    class BuilderWithAllFields {

        @Test
        void should_assignAllFields_when_builderUsedWithFullPayload() {
            Instant due = Instant.parse("2026-05-01T12:00:00Z");
            Instant created = Instant.parse("2026-04-01T10:00:00Z");
            Instant updated = Instant.parse("2026-04-02T11:00:00Z");

            Todo todo = Todo.builder()
                    .id("todo-1")
                    .title("Build feature")
                    .description("Implement feature X")
                    .status(Todo.Status.IN_PROGRESS)
                    .priority(Todo.Priority.HIGH)
                    .tags("backend,test")
                    .projectId("proj-1")
                    .linkedWorkflowId("wf-1")
                    .source(Todo.Source.AI)
                    .sourceAgent("claude-code")
                    .sessionId("session-42")
                    .workflowRunId("run-7")
                    .referenceType("workflow")
                    .referenceId("ref-1")
                    .relatedTodoId("todo-0")
                    .dueDate(due)
                    .fixAttempts(3)
                    .lastFixRunId("fix-run-1")
                    .resolutionState(Todo.ResolutionState.RUNNING)
                    .createdAt(created)
                    .updatedAt(updated)
                    .build();

            assertThat(todo.getId()).isEqualTo("todo-1");
            assertThat(todo.getTitle()).isEqualTo("Build feature");
            assertThat(todo.getDescription()).isEqualTo("Implement feature X");
            assertThat(todo.getStatus()).isEqualTo(Todo.Status.IN_PROGRESS);
            assertThat(todo.getPriority()).isEqualTo(Todo.Priority.HIGH);
            assertThat(todo.getTags()).isEqualTo("backend,test");
            assertThat(todo.getProjectId()).isEqualTo("proj-1");
            assertThat(todo.getLinkedWorkflowId()).isEqualTo("wf-1");
            assertThat(todo.getSource()).isEqualTo(Todo.Source.AI);
            assertThat(todo.getSourceAgent()).isEqualTo("claude-code");
            assertThat(todo.getSessionId()).isEqualTo("session-42");
            assertThat(todo.getWorkflowRunId()).isEqualTo("run-7");
            assertThat(todo.getReferenceType()).isEqualTo("workflow");
            assertThat(todo.getReferenceId()).isEqualTo("ref-1");
            assertThat(todo.getRelatedTodoId()).isEqualTo("todo-0");
            assertThat(todo.getDueDate()).isEqualTo(due);
            assertThat(todo.getFixAttempts()).isEqualTo(3);
            assertThat(todo.getLastFixRunId()).isEqualTo("fix-run-1");
            assertThat(todo.getResolutionState()).isEqualTo(Todo.ResolutionState.RUNNING);
            assertThat(todo.getCreatedAt()).isEqualTo(created);
            assertThat(todo.getUpdatedAt()).isEqualTo(updated);
        }
    }

    @Nested
    class NoArgsAndAllArgsConstructor {

        @Test
        void should_createEmptyInstance_when_noArgsConstructorUsed() {
            Todo todo = new Todo();
            assertThat(todo.getId()).isNull();
            assertThat(todo.getTitle()).isNull();
            // Lombok initializes @Builder.Default fields as field initializers,
            // so even the no-args constructor picks them up
            assertThat(todo.getFixAttempts()).isZero();
            assertThat(todo.getStatus()).isEqualTo(Todo.Status.OPEN);
            assertThat(todo.getPriority()).isEqualTo(Todo.Priority.MEDIUM);
            assertThat(todo.getSource()).isEqualTo(Todo.Source.USER);
        }

        @Test
        void should_assignAllFields_when_allArgsConstructorUsed() {
            Instant now = Instant.now();
            Todo todo = new Todo(
                    "id-1", "title", "desc",
                    Todo.Status.DONE, Todo.Priority.CRITICAL,
                    "tag", "pid", "wfid",
                    Todo.Source.WORKFLOW, "agent", "sid",
                    "4fde1b91-2cce-4ab2-b7d0-fd2a138f9322",
                    "run", "rt", "rid", "ep", "er", "eu", "rel",
                    now, 5, "lastFix",
                    Todo.ResolutionState.SUCCEEDED,
                    "goal-1", 3, "package-1", Todo.Complexity.L, "high", "note", Todo.RatedBy.AI, now, "Frage?",
                    "Stil A", now, Todo.RatedBy.USER,
                    now, now
            );
            assertThat(todo.getGoalId()).isEqualTo("goal-1");
            assertThat(todo.getDecision()).isEqualTo("Stil A");
            assertThat(todo.getDecidedBy()).isEqualTo(Todo.RatedBy.USER);
            assertThat(todo.getDecisionState()).as("die offene Frage schlaegt die Entscheidung").isEqualTo(Todo.DecisionState.QUESTION);
            assertThat(todo.getWorkPackageId()).isEqualTo("package-1");
            assertThat(todo.getGoalRank()).isEqualTo(3);
            assertThat(todo.getComplexity()).isEqualTo(Todo.Complexity.L);
            assertThat(todo.getSuggestedEffort()).isEqualTo("high");
            assertThat(todo.getRatedBy()).isEqualTo(Todo.RatedBy.AI);
            assertThat(todo.getOpenQuestion()).isEqualTo("Frage?");
            assertThat(todo.getId()).isEqualTo("id-1");
            assertThat(todo.getAgentSessionRef())
                    .as("agentSessionRef traegt die Agenten-Konversation, nicht die Shell-Session")
                    .isEqualTo("4fde1b91-2cce-4ab2-b7d0-fd2a138f9322");
            assertThat(todo.getTitle()).isEqualTo("title");
            assertThat(todo.getDescription()).isEqualTo("desc");
            assertThat(todo.getStatus()).isEqualTo(Todo.Status.DONE);
            assertThat(todo.getPriority()).isEqualTo(Todo.Priority.CRITICAL);
            assertThat(todo.getTags()).isEqualTo("tag");
            assertThat(todo.getProjectId()).isEqualTo("pid");
            assertThat(todo.getLinkedWorkflowId()).isEqualTo("wfid");
            assertThat(todo.getSource()).isEqualTo(Todo.Source.WORKFLOW);
            assertThat(todo.getSourceAgent()).isEqualTo("agent");
            assertThat(todo.getSessionId()).isEqualTo("sid");
            assertThat(todo.getWorkflowRunId()).isEqualTo("run");
            assertThat(todo.getReferenceType()).isEqualTo("rt");
            assertThat(todo.getReferenceId()).isEqualTo("rid");
            assertThat(todo.getRelatedTodoId()).isEqualTo("rel");
            assertThat(todo.getDueDate()).isEqualTo(now);
            assertThat(todo.getFixAttempts()).isEqualTo(5);
            assertThat(todo.getLastFixRunId()).isEqualTo("lastFix");
            assertThat(todo.getResolutionState()).isEqualTo(Todo.ResolutionState.SUCCEEDED);
            assertThat(todo.getCreatedAt()).isEqualTo(now);
            assertThat(todo.getUpdatedAt()).isEqualTo(now);
        }
    }

    @Nested
    class Setters {

        @Test
        void should_storeTitle_when_setViaSetter() {
            Todo todo = new Todo();
            todo.setTitle("new title");
            assertThat(todo.getTitle()).isEqualTo("new title");
        }

        @Test
        void should_storeStatus_when_setViaSetter() {
            Todo todo = new Todo();
            todo.setStatus(Todo.Status.BACKLOG);
            assertThat(todo.getStatus()).isEqualTo(Todo.Status.BACKLOG);
        }

        @Test
        void should_storePriority_when_setViaSetter() {
            Todo todo = new Todo();
            todo.setPriority(Todo.Priority.LOW);
            assertThat(todo.getPriority()).isEqualTo(Todo.Priority.LOW);
        }

        @Test
        void should_storeSource_when_setViaSetter() {
            Todo todo = new Todo();
            todo.setSource(Todo.Source.AI);
            assertThat(todo.getSource()).isEqualTo(Todo.Source.AI);
        }

        @Test
        void should_storeResolutionState_when_setViaSetter() {
            Todo todo = new Todo();
            todo.setResolutionState(Todo.ResolutionState.FAILED);
            assertThat(todo.getResolutionState()).isEqualTo(Todo.ResolutionState.FAILED);
        }

        @Test
        void should_storeFixAttempts_when_setViaSetter() {
            Todo todo = new Todo();
            todo.setFixAttempts(7);
            assertThat(todo.getFixAttempts()).isEqualTo(7);
        }

        @Test
        void should_storeSessionIdAndSourceAgent_when_setViaSetters() {
            Todo todo = new Todo();
            todo.setSessionId("sess-1");
            todo.setSourceAgent("mySuricates");
            assertThat(todo.getSessionId()).isEqualTo("sess-1");
            assertThat(todo.getSourceAgent()).isEqualTo("mySuricates");
        }

        @Test
        void should_acceptNull_when_optionalFieldsClearedViaSetter() {
            Todo todo = Todo.builder().title("t").description("d").tags("a").build();
            todo.setDescription(null);
            todo.setTags(null);
            assertThat(todo.getDescription()).isNull();
            assertThat(todo.getTags()).isNull();
        }
    }

    @Nested
    class StatusEnum {

        @Test
        void should_containAllStatusValues_when_valuesCalled() {
            assertThat(Todo.Status.values())
                    .containsExactly(Todo.Status.OPEN, Todo.Status.IN_PROGRESS,
                            Todo.Status.DONE, Todo.Status.BACKLOG, Todo.Status.ARCHIVED);
        }

        @Test
        void should_resolveStatusByName_when_valueOfCalled() {
            assertThat(Todo.Status.valueOf("OPEN")).isEqualTo(Todo.Status.OPEN);
            assertThat(Todo.Status.valueOf("IN_PROGRESS")).isEqualTo(Todo.Status.IN_PROGRESS);
            assertThat(Todo.Status.valueOf("DONE")).isEqualTo(Todo.Status.DONE);
            assertThat(Todo.Status.valueOf("BACKLOG")).isEqualTo(Todo.Status.BACKLOG);
            assertThat(Todo.Status.valueOf("ARCHIVED")).isEqualTo(Todo.Status.ARCHIVED);
        }

        @Test
        void should_throwIllegalArgument_when_statusValueOfReceivesUnknown() {
            assertThatThrownBy(() -> Todo.Status.valueOf("UNKNOWN"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class PriorityEnum {

        @Test
        void should_containAllPriorityValues_when_valuesCalled() {
            assertThat(Todo.Priority.values())
                    .containsExactly(Todo.Priority.LOW, Todo.Priority.MEDIUM,
                            Todo.Priority.HIGH, Todo.Priority.CRITICAL);
        }

        @Test
        void should_resolvePriorityByName_when_valueOfCalled() {
            assertThat(Todo.Priority.valueOf("CRITICAL")).isEqualTo(Todo.Priority.CRITICAL);
        }

        @Test
        void should_throwIllegalArgument_when_priorityValueOfReceivesUnknown() {
            assertThatThrownBy(() -> Todo.Priority.valueOf("URGENT"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class SourceEnum {

        @Test
        void should_containAllSourceValues_when_valuesCalled() {
            assertThat(Todo.Source.values())
                    .containsExactly(Todo.Source.USER, Todo.Source.AI, Todo.Source.WORKFLOW);
        }

        @Test
        void should_resolveSourceByName_when_valueOfCalled() {
            assertThat(Todo.Source.valueOf("AI")).isEqualTo(Todo.Source.AI);
        }
    }

    @Nested
    class ResolutionStateEnum {

        @Test
        void should_containAllResolutionStateValues_when_valuesCalled() {
            assertThat(Todo.ResolutionState.values())
                    .containsExactly(Todo.ResolutionState.IDLE, Todo.ResolutionState.RUNNING,
                            Todo.ResolutionState.FAILED, Todo.ResolutionState.SUCCEEDED);
        }

        @Test
        void should_resolveResolutionStateByName_when_valueOfCalled() {
            assertThat(Todo.ResolutionState.valueOf("SUCCEEDED")).isEqualTo(Todo.ResolutionState.SUCCEEDED);
        }
    }
}
