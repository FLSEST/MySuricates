package com.automation.dto.response;

import com.automation.entity.RunStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChainRunResponseTest {

    // --- ChainRunResponse: Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructed() {
        var now = Instant.now();
        var later = now.plusSeconds(10);
        var entryRuns = List.<ChainRunResponse.ChainRunEntryResponse>of();
        var inputContext = Map.<String, Object>of("key", "value");
        var metadata = Map.<String, Object>of("meta", 42);

        var response = new ChainRunResponse(
                "run-1", "chain-42", "My Chain",
                RunStatus.COMPLETED, entryRuns, inputContext,
                now, later, 10000L, null, metadata, "sched-1"
        );

        assertThat(response.id()).isEqualTo("run-1");
        assertThat(response.chainId()).isEqualTo("chain-42");
        assertThat(response.chainName()).isEqualTo("My Chain");
        assertThat(response.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(response.entryRuns()).isEmpty();
        assertThat(response.inputContext()).isEqualTo(inputContext);
        assertThat(response.startedAt()).isEqualTo(now);
        assertThat(response.completedAt()).isEqualTo(later);
        assertThat(response.durationMs()).isEqualTo(10000L);
        assertThat(response.error()).isNull();
        assertThat(response.metadata()).isEqualTo(metadata);
        assertThat(response.triggeredByScheduleId()).isEqualTo("sched-1");
    }

    @Test
    void should_storeErrorMessage_when_failed() {
        var response = new ChainRunResponse(
                "run-2", "chain-1", "Chain",
                RunStatus.FAILED, null, null,
                Instant.now(), null, null, "Something went wrong", null, null
        );

        assertThat(response.status()).isEqualTo(RunStatus.FAILED);
        assertThat(response.error()).isEqualTo("Something went wrong");
        assertThat(response.completedAt()).isNull();
        assertThat(response.durationMs()).isNull();
    }

    @Test
    void should_acceptAllNulls_when_constructed() {
        var response = new ChainRunResponse(
                null, null, null, null, null, null,
                null, null, null, null, null, null
        );

        assertThat(response.id()).isNull();
        assertThat(response.chainId()).isNull();
        assertThat(response.chainName()).isNull();
        assertThat(response.status()).isNull();
        assertThat(response.entryRuns()).isNull();
        assertThat(response.inputContext()).isNull();
        assertThat(response.startedAt()).isNull();
        assertThat(response.completedAt()).isNull();
        assertThat(response.durationMs()).isNull();
        assertThat(response.error()).isNull();
        assertThat(response.metadata()).isNull();
        assertThat(response.triggeredByScheduleId()).isNull();
    }

    @Test
    void should_storeAllRunStatuses_when_constructed() {
        for (RunStatus status : RunStatus.values()) {
            var response = new ChainRunResponse(
                    "run-1", "chain-1", "Chain",
                    status, null, null,
                    null, null, null, null, null, null
            );
            assertThat(response.status()).isEqualTo(status);
        }
    }

    @Test
    void should_storeEntryRuns_when_listProvided() {
        var entry1 = new ChainRunResponse.ChainRunEntryResponse(
                0, "wf-1", "Workflow 1", "Project A",
                "wfrun-1", RunStatus.COMPLETED,
                Instant.now(), Instant.now().plusSeconds(5), 5000L
        );
        var entry2 = new ChainRunResponse.ChainRunEntryResponse(
                1, "wf-2", "Workflow 2", "Project B",
                "wfrun-2", RunStatus.FAILED,
                Instant.now(), null, null
        );
        var response = new ChainRunResponse(
                "run-1", "chain-1", "Chain",
                RunStatus.COMPLETED_WITH_ERRORS, List.of(entry1, entry2), null,
                null, null, null, null, null, null
        );

        assertThat(response.entryRuns()).hasSize(2);
        assertThat(response.entryRuns().get(0)).isEqualTo(entry1);
        assertThat(response.entryRuns().get(1)).isEqualTo(entry2);
    }

    @Test
    void should_storeInputContext_when_mapProvided() {
        var context = Map.<String, Object>of("param1", "value1", "param2", 99);
        var response = new ChainRunResponse(
                "run-1", "chain-1", "Chain",
                RunStatus.RUNNING, null, context,
                null, null, null, null, null, null
        );

        assertThat(response.inputContext()).containsEntry("param1", "value1");
        assertThat(response.inputContext()).containsEntry("param2", 99);
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var now = Instant.now();
        var a = new ChainRunResponse(
                "run-1", "chain-1", "Chain",
                RunStatus.COMPLETED, List.of(), Map.of(),
                now, null, 1000L, null, Map.of(), null
        );
        var b = new ChainRunResponse(
                "run-1", "chain-1", "Chain",
                RunStatus.COMPLETED, List.of(), Map.of(),
                now, null, 1000L, null, Map.of(), null
        );

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        var a = new ChainRunResponse("run-1", "chain-1", "Chain", RunStatus.COMPLETED, null, null, null, null, null, null, null, null);
        var b = new ChainRunResponse("run-2", "chain-1", "Chain", RunStatus.COMPLETED, null, null, null, null, null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentStatus() {
        var a = new ChainRunResponse("run-1", "chain-1", "Chain", RunStatus.COMPLETED, null, null, null, null, null, null, null, null);
        var b = new ChainRunResponse("run-1", "chain-1", "Chain", RunStatus.FAILED, null, null, null, null, null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentChainName() {
        var a = new ChainRunResponse("run-1", "chain-1", "Chain A", RunStatus.COMPLETED, null, null, null, null, null, null, null, null);
        var b = new ChainRunResponse("run-1", "chain-1", "Chain B", RunStatus.COMPLETED, null, null, null, null, null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_bothAllNull() {
        var a = new ChainRunResponse(null, null, null, null, null, null, null, null, null, null, null, null);
        var b = new ChainRunResponse(null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var response = new ChainRunResponse(
                "run-99", "chain-42", "My Chain",
                RunStatus.RUNNING, null, null,
                null, null, null, null, null, "sched-7"
        );
        var str = response.toString();

        assertThat(str).contains("run-99");
        assertThat(str).contains("chain-42");
        assertThat(str).contains("My Chain");
        assertThat(str).contains("RUNNING");
        assertThat(str).contains("sched-7");
    }

    // --- ChainRunEntryResponse: Constructor & accessors ---

    @Test
    void should_setAllFields_when_entryConstructed() {
        var now = Instant.now();
        var later = now.plusSeconds(3);
        var entry = new ChainRunResponse.ChainRunEntryResponse(
                2, "wf-5", "My Workflow", "Project X",
                "wfrun-88", RunStatus.COMPLETED,
                now, later, 3000L
        );

        assertThat(entry.entryIndex()).isEqualTo(2);
        assertThat(entry.workflowId()).isEqualTo("wf-5");
        assertThat(entry.workflowName()).isEqualTo("My Workflow");
        assertThat(entry.projectName()).isEqualTo("Project X");
        assertThat(entry.workflowRunId()).isEqualTo("wfrun-88");
        assertThat(entry.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(entry.startedAt()).isEqualTo(now);
        assertThat(entry.completedAt()).isEqualTo(later);
        assertThat(entry.durationMs()).isEqualTo(3000L);
    }

    @Test
    void should_acceptNullsInEntry_when_constructed() {
        var entry = new ChainRunResponse.ChainRunEntryResponse(
                0, null, null, null, null, null, null, null, null
        );

        assertThat(entry.entryIndex()).isZero();
        assertThat(entry.workflowId()).isNull();
        assertThat(entry.workflowName()).isNull();
        assertThat(entry.projectName()).isNull();
        assertThat(entry.workflowRunId()).isNull();
        assertThat(entry.status()).isNull();
        assertThat(entry.startedAt()).isNull();
        assertThat(entry.completedAt()).isNull();
        assertThat(entry.durationMs()).isNull();
    }

    @Test
    void should_beEqual_when_entryHasSameFieldValues() {
        var now = Instant.now();
        var a = new ChainRunResponse.ChainRunEntryResponse(0, "wf-1", "WF", "Proj", "run-1", RunStatus.PENDING, now, null, null);
        var b = new ChainRunResponse.ChainRunEntryResponse(0, "wf-1", "WF", "Proj", "run-1", RunStatus.PENDING, now, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_entryIndexDiffers() {
        var a = new ChainRunResponse.ChainRunEntryResponse(0, "wf-1", "WF", "Proj", "run-1", RunStatus.COMPLETED, null, null, null);
        var b = new ChainRunResponse.ChainRunEntryResponse(1, "wf-1", "WF", "Proj", "run-1", RunStatus.COMPLETED, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_entryWorkflowIdDiffers() {
        var a = new ChainRunResponse.ChainRunEntryResponse(0, "wf-1", "WF", "Proj", "run-1", RunStatus.COMPLETED, null, null, null);
        var b = new ChainRunResponse.ChainRunEntryResponse(0, "wf-2", "WF", "Proj", "run-1", RunStatus.COMPLETED, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldValues_when_entryToStringCalled() {
        var entry = new ChainRunResponse.ChainRunEntryResponse(
                3, "wf-7", "My Flow", "Project Z",
                "run-55", RunStatus.CANCELLED, null, null, null
        );
        var str = entry.toString();

        assertThat(str).contains("wf-7");
        assertThat(str).contains("My Flow");
        assertThat(str).contains("Project Z");
        assertThat(str).contains("run-55");
        assertThat(str).contains("CANCELLED");
    }
}
