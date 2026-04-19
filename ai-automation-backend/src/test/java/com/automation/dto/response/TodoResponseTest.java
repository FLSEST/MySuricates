package com.automation.dto.response;

import com.automation.entity.Todo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TodoResponseTest {

    private static final String ID = "todo-123";
    private static final String TITLE = "Fix critical bug";
    private static final String DESCRIPTION = "Something is broken";
    private static final Todo.Status STATUS = Todo.Status.OPEN;
    private static final Todo.Priority PRIORITY = Todo.Priority.HIGH;
    private static final List<String> TAGS = List.of("backend", "bug");
    private static final String PROJECT_ID = "proj-456";
    private static final String PROJECT_NAME = "AutoPilot";
    private static final String LINKED_WORKFLOW_ID = "wf-789";
    private static final String LINKED_WORKFLOW_NAME = "Bug Resolver";
    private static final Todo.Source SOURCE = Todo.Source.AI;
    private static final String SOURCE_AGENT = "claude-code";
    private static final String SESSION_ID = "session-789";
    private static final String WORKFLOW_RUN_ID = "run-abc";
    private static final String REFERENCE_TYPE = "WORKFLOW";
    private static final String REFERENCE_ID = "ref-def";
    private static final int FIX_ATTEMPTS = 3;
    private static final String LAST_FIX_RUN_ID = "fix-run-001";
    private static final Todo.ResolutionState RESOLUTION_STATE = Todo.ResolutionState.RUNNING;
    private static final Instant DUE_DATE = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-04-01T10:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-04-10T15:30:00Z");

    private TodoResponse createFullResponse() {
        return new TodoResponse(
                ID, TITLE, DESCRIPTION, STATUS, PRIORITY, TAGS,
                PROJECT_ID, PROJECT_NAME, LINKED_WORKFLOW_ID, LINKED_WORKFLOW_NAME, SOURCE, SOURCE_AGENT, SESSION_ID,
                WORKFLOW_RUN_ID, REFERENCE_TYPE, REFERENCE_ID, FIX_ATTEMPTS,
                LAST_FIX_RUN_ID, RESOLUTION_STATE, DUE_DATE, CREATED_AT, UPDATED_AT
        );
    }

    // --- Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructed() {
        var response = createFullResponse();

        assertThat(response.id()).isEqualTo(ID);
        assertThat(response.title()).isEqualTo(TITLE);
        assertThat(response.description()).isEqualTo(DESCRIPTION);
        assertThat(response.status()).isEqualTo(STATUS);
        assertThat(response.priority()).isEqualTo(PRIORITY);
        assertThat(response.tags()).isEqualTo(TAGS);
        assertThat(response.projectId()).isEqualTo(PROJECT_ID);
        assertThat(response.projectName()).isEqualTo(PROJECT_NAME);
        assertThat(response.linkedWorkflowId()).isEqualTo(LINKED_WORKFLOW_ID);
        assertThat(response.linkedWorkflowName()).isEqualTo(LINKED_WORKFLOW_NAME);
        assertThat(response.source()).isEqualTo(SOURCE);
        assertThat(response.sourceAgent()).isEqualTo(SOURCE_AGENT);
        assertThat(response.sessionId()).isEqualTo(SESSION_ID);
        assertThat(response.workflowRunId()).isEqualTo(WORKFLOW_RUN_ID);
        assertThat(response.referenceType()).isEqualTo(REFERENCE_TYPE);
        assertThat(response.referenceId()).isEqualTo(REFERENCE_ID);
        assertThat(response.fixAttempts()).isEqualTo(FIX_ATTEMPTS);
        assertThat(response.lastFixRunId()).isEqualTo(LAST_FIX_RUN_ID);
        assertThat(response.resolutionState()).isEqualTo(RESOLUTION_STATE);
        assertThat(response.dueDate()).isEqualTo(DUE_DATE);
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);
        assertThat(response.updatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    void should_acceptNullStrings_when_constructed() {
        var response = new TodoResponse(
                null, null, null, STATUS, PRIORITY, TAGS,
                null, null, null, null, SOURCE, null, null,
                null, null, null, 0,
                null, null, null, null, null
        );

        assertThat(response.id()).isNull();
        assertThat(response.title()).isNull();
        assertThat(response.description()).isNull();
        assertThat(response.projectId()).isNull();
        assertThat(response.projectName()).isNull();
        assertThat(response.linkedWorkflowId()).isNull();
        assertThat(response.linkedWorkflowName()).isNull();
        assertThat(response.sourceAgent()).isNull();
        assertThat(response.sessionId()).isNull();
        assertThat(response.workflowRunId()).isNull();
        assertThat(response.referenceType()).isNull();
        assertThat(response.referenceId()).isNull();
        assertThat(response.lastFixRunId()).isNull();
        assertThat(response.resolutionState()).isNull();
        assertThat(response.dueDate()).isNull();
        assertThat(response.createdAt()).isNull();
        assertThat(response.updatedAt()).isNull();
    }

    @Test
    void should_acceptNullEnums_when_constructed() {
        var response = new TodoResponse(
                ID, TITLE, DESCRIPTION, null, null, TAGS,
                PROJECT_ID, PROJECT_NAME, LINKED_WORKFLOW_ID, LINKED_WORKFLOW_NAME, null, SOURCE_AGENT, SESSION_ID,
                WORKFLOW_RUN_ID, REFERENCE_TYPE, REFERENCE_ID, 0,
                LAST_FIX_RUN_ID, RESOLUTION_STATE, DUE_DATE, CREATED_AT, UPDATED_AT
        );

        assertThat(response.status()).isNull();
        assertThat(response.priority()).isNull();
        assertThat(response.source()).isNull();
    }

    @Test
    void should_acceptNullTags_when_constructed() {
        var response = new TodoResponse(
                ID, TITLE, DESCRIPTION, STATUS, PRIORITY, null,
                PROJECT_ID, PROJECT_NAME, LINKED_WORKFLOW_ID, LINKED_WORKFLOW_NAME, SOURCE, SOURCE_AGENT, SESSION_ID,
                WORKFLOW_RUN_ID, REFERENCE_TYPE, REFERENCE_ID, 0,
                LAST_FIX_RUN_ID, RESOLUTION_STATE, DUE_DATE, CREATED_AT, UPDATED_AT
        );

        assertThat(response.tags()).isNull();
    }

    @Test
    void should_acceptEmptyTags_when_constructed() {
        var response = new TodoResponse(
                ID, TITLE, DESCRIPTION, STATUS, PRIORITY, List.of(),
                PROJECT_ID, PROJECT_NAME, LINKED_WORKFLOW_ID, LINKED_WORKFLOW_NAME, SOURCE, SOURCE_AGENT, SESSION_ID,
                WORKFLOW_RUN_ID, REFERENCE_TYPE, REFERENCE_ID, 0,
                LAST_FIX_RUN_ID, RESOLUTION_STATE, DUE_DATE, CREATED_AT, UPDATED_AT
        );

        assertThat(response.tags()).isEmpty();
    }

    @Test
    void should_acceptAllNulls_when_constructed() {
        var response = new TodoResponse(
                null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, 0,
                null, null, null, null, null
        );

        assertThat(response.id()).isNull();
        assertThat(response.title()).isNull();
        assertThat(response.description()).isNull();
        assertThat(response.status()).isNull();
        assertThat(response.priority()).isNull();
        assertThat(response.tags()).isNull();
        assertThat(response.projectId()).isNull();
        assertThat(response.projectName()).isNull();
        assertThat(response.linkedWorkflowId()).isNull();
        assertThat(response.linkedWorkflowName()).isNull();
        assertThat(response.source()).isNull();
        assertThat(response.sourceAgent()).isNull();
        assertThat(response.sessionId()).isNull();
        assertThat(response.workflowRunId()).isNull();
        assertThat(response.referenceType()).isNull();
        assertThat(response.referenceId()).isNull();
        assertThat(response.fixAttempts()).isZero();
        assertThat(response.lastFixRunId()).isNull();
        assertThat(response.resolutionState()).isNull();
        assertThat(response.dueDate()).isNull();
        assertThat(response.createdAt()).isNull();
        assertThat(response.updatedAt()).isNull();
    }

    @Test
    void should_returnZeroFixAttempts_when_constructedWithZero() {
        var response = createFullResponse();
        var zeroAttempts = new TodoResponse(
                ID, TITLE, DESCRIPTION, STATUS, PRIORITY, TAGS,
                PROJECT_ID, PROJECT_NAME, LINKED_WORKFLOW_ID, LINKED_WORKFLOW_NAME, SOURCE, SOURCE_AGENT, SESSION_ID,
                WORKFLOW_RUN_ID, REFERENCE_TYPE, REFERENCE_ID, 0,
                LAST_FIX_RUN_ID, RESOLUTION_STATE, DUE_DATE, CREATED_AT, UPDATED_AT
        );

        assertThat(response.fixAttempts()).isEqualTo(3);
        assertThat(zeroAttempts.fixAttempts()).isZero();
    }

    // --- Enum values ---

    @Test
    void should_acceptAllStatusValues_when_constructed() {
        for (Todo.Status status : Todo.Status.values()) {
            var response = new TodoResponse(
                    ID, TITLE, DESCRIPTION, status, PRIORITY, TAGS,
                    PROJECT_ID, PROJECT_NAME, LINKED_WORKFLOW_ID, LINKED_WORKFLOW_NAME, SOURCE, SOURCE_AGENT, SESSION_ID,
                    WORKFLOW_RUN_ID, REFERENCE_TYPE, REFERENCE_ID, 0,
                    LAST_FIX_RUN_ID, RESOLUTION_STATE, DUE_DATE, CREATED_AT, UPDATED_AT
            );
            assertThat(response.status()).isEqualTo(status);
        }
    }

    @Test
    void should_acceptAllPriorityValues_when_constructed() {
        for (Todo.Priority priority : Todo.Priority.values()) {
            var response = new TodoResponse(
                    ID, TITLE, DESCRIPTION, STATUS, priority, TAGS,
                    PROJECT_ID, PROJECT_NAME, LINKED_WORKFLOW_ID, LINKED_WORKFLOW_NAME, SOURCE, SOURCE_AGENT, SESSION_ID,
                    WORKFLOW_RUN_ID, REFERENCE_TYPE, REFERENCE_ID, 0,
                    LAST_FIX_RUN_ID, RESOLUTION_STATE, DUE_DATE, CREATED_AT, UPDATED_AT
            );
            assertThat(response.priority()).isEqualTo(priority);
        }
    }

    @Test
    void should_acceptAllSourceValues_when_constructed() {
        for (Todo.Source source : Todo.Source.values()) {
            var response = new TodoResponse(
                    ID, TITLE, DESCRIPTION, STATUS, PRIORITY, TAGS,
                    PROJECT_ID, PROJECT_NAME, LINKED_WORKFLOW_ID, LINKED_WORKFLOW_NAME, source, SOURCE_AGENT, SESSION_ID,
                    WORKFLOW_RUN_ID, REFERENCE_TYPE, REFERENCE_ID, 0,
                    LAST_FIX_RUN_ID, RESOLUTION_STATE, DUE_DATE, CREATED_AT, UPDATED_AT
            );
            assertThat(response.source()).isEqualTo(source);
        }
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var a = createFullResponse();
        var b = createFullResponse();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothAllNull() {
        var a = new TodoResponse(
                null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, 0,
                null, null, null, null, null
        );
        var b = new TodoResponse(
                null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, 0,
                null, null, null, null, null
        );

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        var a = createFullResponse();
        var b = new TodoResponse("other-id", TITLE, DESCRIPTION, STATUS, PRIORITY, TAGS, PROJECT_ID, PROJECT_NAME, LINKED_WORKFLOW_ID, LINKED_WORKFLOW_NAME, SOURCE, SOURCE_AGENT, SESSION_ID, WORKFLOW_RUN_ID, REFERENCE_TYPE, REFERENCE_ID, FIX_ATTEMPTS, LAST_FIX_RUN_ID, RESOLUTION_STATE, DUE_DATE, CREATED_AT, UPDATED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentTitle() {
        var a = createFullResponse();
        var b = new TodoResponse(ID, "Different title", DESCRIPTION, STATUS, PRIORITY, TAGS, PROJECT_ID, PROJECT_NAME, LINKED_WORKFLOW_ID, LINKED_WORKFLOW_NAME, SOURCE, SOURCE_AGENT, SESSION_ID, WORKFLOW_RUN_ID, REFERENCE_TYPE, REFERENCE_ID, FIX_ATTEMPTS, LAST_FIX_RUN_ID, RESOLUTION_STATE, DUE_DATE, CREATED_AT, UPDATED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentStatus() {
        var a = createFullResponse();
        var b = new TodoResponse(ID, TITLE, DESCRIPTION, Todo.Status.DONE, PRIORITY, TAGS, PROJECT_ID, PROJECT_NAME, LINKED_WORKFLOW_ID, LINKED_WORKFLOW_NAME, SOURCE, SOURCE_AGENT, SESSION_ID, WORKFLOW_RUN_ID, REFERENCE_TYPE, REFERENCE_ID, FIX_ATTEMPTS, LAST_FIX_RUN_ID, RESOLUTION_STATE, DUE_DATE, CREATED_AT, UPDATED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentFixAttempts() {
        var a = createFullResponse();
        var b = new TodoResponse(ID, TITLE, DESCRIPTION, STATUS, PRIORITY, TAGS, PROJECT_ID, PROJECT_NAME, LINKED_WORKFLOW_ID, LINKED_WORKFLOW_NAME, SOURCE, SOURCE_AGENT, SESSION_ID, WORKFLOW_RUN_ID, REFERENCE_TYPE, REFERENCE_ID, 99, LAST_FIX_RUN_ID, RESOLUTION_STATE, DUE_DATE, CREATED_AT, UPDATED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var response = createFullResponse();

        assertThat(response).isNotEqualTo(null);
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var response = createFullResponse();
        var str = response.toString();

        assertThat(str).contains(ID);
        assertThat(str).contains(TITLE);
        assertThat(str).contains(DESCRIPTION);
        assertThat(str).contains("OPEN");
        assertThat(str).contains("HIGH");
        assertThat(str).contains("backend");
        assertThat(str).contains(PROJECT_ID);
        assertThat(str).contains(PROJECT_NAME);
        assertThat(str).contains("AI");
        assertThat(str).contains(SOURCE_AGENT);
    }

    @Test
    void should_handleNullsInToString_when_fieldsAreNull() {
        var response = new TodoResponse(
                null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, 0,
                null, null, null, null, null
        );
        var str = response.toString();

        assertThat(str).contains("null");
    }
}
