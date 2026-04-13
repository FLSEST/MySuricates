package com.automation.dto.response;

import com.automation.entity.WishStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WishResponseTest {

    private static final Instant NOW = Instant.parse("2026-04-13T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-04-13T12:00:00Z");

    private WishResponse createFullResponse() {
        return new WishResponse(
                "wish-1",
                "Deploy my app",
                WishStatus.EXECUTING,
                "proj-42",
                "My Project",
                Map.of("steps", List.of("build", "deploy")),
                List.of("wf-1", "wf-2"),
                List.of("Build Workflow", "Deploy Workflow"),
                List.of("ref-1"),
                List.of("Reference Workflow"),
                "chain-1",
                3,
                5,
                "run-99",
                "Deployed successfully",
                null,
                List.of(Map.of("iteration", 1, "status", "done")),
                Map.of("source", "ui"),
                NOW,
                LATER
        );
    }

    // --- Constructor & accessors ---

    @Test
    void should_returnAllFields_when_constructedWithAllValues() {
        var response = createFullResponse();

        assertThat(response.id()).isEqualTo("wish-1");
        assertThat(response.originalText()).isEqualTo("Deploy my app");
        assertThat(response.status()).isEqualTo(WishStatus.EXECUTING);
        assertThat(response.projectId()).isEqualTo("proj-42");
        assertThat(response.projectName()).isEqualTo("My Project");
        assertThat(response.decomposition()).containsKey("steps");
        assertThat(response.generatedWorkflowIds()).containsExactly("wf-1", "wf-2");
        assertThat(response.generatedWorkflowNames()).containsExactly("Build Workflow", "Deploy Workflow");
        assertThat(response.referenceWorkflowIds()).containsExactly("ref-1");
        assertThat(response.referenceWorkflowNames()).containsExactly("Reference Workflow");
        assertThat(response.generatedChainId()).isEqualTo("chain-1");
        assertThat(response.iterationCount()).isEqualTo(3);
        assertThat(response.maxIterations()).isEqualTo(5);
        assertThat(response.currentRunId()).isEqualTo("run-99");
        assertThat(response.resultSummary()).isEqualTo("Deployed successfully");
        assertThat(response.error()).isNull();
        assertThat(response.iterationHistory()).hasSize(1);
        assertThat(response.metadata()).containsEntry("source", "ui");
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.updatedAt()).isEqualTo(LATER);
    }

    @Test
    void should_acceptEmptyCollections_when_constructed() {
        var response = new WishResponse(
                "wish-2", "text", WishStatus.RECEIVED, null, null,
                Map.of(), List.of(), List.of(), List.of(), List.of(),
                null, 0, 10, null, null, null,
                List.of(), Map.of(), NOW, NOW
        );

        assertThat(response.decomposition()).isEmpty();
        assertThat(response.generatedWorkflowIds()).isEmpty();
        assertThat(response.generatedWorkflowNames()).isEmpty();
        assertThat(response.referenceWorkflowIds()).isEmpty();
        assertThat(response.referenceWorkflowNames()).isEmpty();
        assertThat(response.iterationHistory()).isEmpty();
        assertThat(response.metadata()).isEmpty();
    }

    @Test
    void should_acceptAllNulls_when_constructed() {
        var response = new WishResponse(
                null, null, null, null, null,
                null, null, null, null, null,
                null, 0, 0, null, null, null,
                null, null, null, null
        );

        assertThat(response.id()).isNull();
        assertThat(response.originalText()).isNull();
        assertThat(response.status()).isNull();
        assertThat(response.projectId()).isNull();
        assertThat(response.projectName()).isNull();
        assertThat(response.decomposition()).isNull();
        assertThat(response.generatedWorkflowIds()).isNull();
        assertThat(response.generatedWorkflowNames()).isNull();
        assertThat(response.referenceWorkflowIds()).isNull();
        assertThat(response.referenceWorkflowNames()).isNull();
        assertThat(response.generatedChainId()).isNull();
        assertThat(response.currentRunId()).isNull();
        assertThat(response.resultSummary()).isNull();
        assertThat(response.error()).isNull();
        assertThat(response.iterationHistory()).isNull();
        assertThat(response.metadata()).isNull();
        assertThat(response.createdAt()).isNull();
        assertThat(response.updatedAt()).isNull();
    }

    @Test
    void should_returnErrorMessage_when_statusIsFailed() {
        var response = new WishResponse(
                "wish-err", "broken wish", WishStatus.FAILED, null, null,
                null, List.of(), List.of(), List.of(), List.of(),
                null, 1, 3, null, null, "Something went wrong",
                List.of(), null, NOW, LATER
        );

        assertThat(response.status()).isEqualTo(WishStatus.FAILED);
        assertThat(response.error()).isEqualTo("Something went wrong");
        assertThat(response.resultSummary()).isNull();
    }

    @Test
    void should_trackIterations_when_multipleIterationsPresent() {
        var history = List.<Map<String, Object>>of(
                Map.of("iteration", 1, "status", "failed"),
                Map.of("iteration", 2, "status", "success")
        );

        var response = new WishResponse(
                "wish-iter", "iterative wish", WishStatus.COMPLETED, null, null,
                null, List.of("wf-1"), List.of("WF"), List.of(), List.of(),
                null, 2, 5, null, "Done", null,
                history, null, NOW, LATER
        );

        assertThat(response.iterationCount()).isEqualTo(2);
        assertThat(response.maxIterations()).isEqualTo(5);
        assertThat(response.iterationHistory()).hasSize(2);
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
    void should_beEqual_when_bothAllNulls() {
        var a = new WishResponse(null, null, null, null, null, null, null, null, null, null, null, 0, 0, null, null, null, null, null, null, null);
        var b = new WishResponse(null, null, null, null, null, null, null, null, null, null, null, 0, 0, null, null, null, null, null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        var a = createFullResponse();
        var b = new WishResponse(
                "wish-other", "Deploy my app", WishStatus.EXECUTING, "proj-42", "My Project",
                Map.of("steps", List.of("build", "deploy")),
                List.of("wf-1", "wf-2"), List.of("Build Workflow", "Deploy Workflow"),
                List.of("ref-1"), List.of("Reference Workflow"),
                "chain-1", 3, 5, "run-99", "Deployed successfully", null,
                List.of(Map.of("iteration", 1, "status", "done")),
                Map.of("source", "ui"), NOW, LATER
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentStatus() {
        var a = createFullResponse();
        var b = new WishResponse(
                "wish-1", "Deploy my app", WishStatus.COMPLETED, "proj-42", "My Project",
                Map.of("steps", List.of("build", "deploy")),
                List.of("wf-1", "wf-2"), List.of("Build Workflow", "Deploy Workflow"),
                List.of("ref-1"), List.of("Reference Workflow"),
                "chain-1", 3, 5, "run-99", "Deployed successfully", null,
                List.of(Map.of("iteration", 1, "status", "done")),
                Map.of("source", "ui"), NOW, LATER
        );

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

        assertThat(str).contains("wish-1");
        assertThat(str).contains("Deploy my app");
        assertThat(str).contains("EXECUTING");
        assertThat(str).contains("proj-42");
        assertThat(str).contains("chain-1");
        assertThat(str).contains("Deployed successfully");
    }

    @Test
    void should_containFieldNames_when_toStringCalled() {
        var response = createFullResponse();
        var str = response.toString();

        assertThat(str).contains("id");
        assertThat(str).contains("originalText");
        assertThat(str).contains("status");
        assertThat(str).contains("iterationCount");
    }
}
