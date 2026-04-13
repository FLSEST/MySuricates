package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChainResponseTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:30:00Z");
    private static final Instant LATER = Instant.parse("2026-01-15T12:00:00Z");

    // ============================================================
    // ChainEntryResponse — Constructor & accessors
    // ============================================================

    @Test
    void should_setAllEntryFields_when_entryConstructed() {
        var ctx = Map.<String, Object>of("key", "value");
        var entry = new ChainResponse.ChainEntryResponse(
                "wf-1", "My Workflow", "Project A", "Step 1", 0, true, ctx);

        assertThat(entry.workflowId()).isEqualTo("wf-1");
        assertThat(entry.workflowName()).isEqualTo("My Workflow");
        assertThat(entry.projectName()).isEqualTo("Project A");
        assertThat(entry.label()).isEqualTo("Step 1");
        assertThat(entry.sortOrder()).isZero();
        assertThat(entry.stopOnFailure()).isTrue();
        assertThat(entry.inputContext()).containsEntry("key", "value");
    }

    @Test
    void should_acceptNullFields_when_entryConstructed() {
        var entry = new ChainResponse.ChainEntryResponse(
                null, null, null, null, 0, false, null);

        assertThat(entry.workflowId()).isNull();
        assertThat(entry.workflowName()).isNull();
        assertThat(entry.projectName()).isNull();
        assertThat(entry.label()).isNull();
        assertThat(entry.sortOrder()).isZero();
        assertThat(entry.stopOnFailure()).isFalse();
        assertThat(entry.inputContext()).isNull();
    }

    @Test
    void should_acceptEmptyInputContext_when_entryConstructed() {
        var entry = new ChainResponse.ChainEntryResponse(
                "wf-1", "WF", "Proj", "Label", 5, false, Map.of());

        assertThat(entry.inputContext()).isEmpty();
        assertThat(entry.sortOrder()).isEqualTo(5);
    }

    // ============================================================
    // ChainEntryResponse — Equality & hashCode
    // ============================================================

    @Test
    void should_beEqualEntries_when_sameFieldValues() {
        var a = new ChainResponse.ChainEntryResponse(
                "wf-1", "WF", "Proj", "L", 1, true, Map.of("k", "v"));
        var b = new ChainResponse.ChainEntryResponse(
                "wf-1", "WF", "Proj", "L", 1, true, Map.of("k", "v"));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqualEntries_when_differentWorkflowId() {
        var a = new ChainResponse.ChainEntryResponse(
                "wf-1", "WF", "Proj", "L", 1, true, null);
        var b = new ChainResponse.ChainEntryResponse(
                "wf-2", "WF", "Proj", "L", 1, true, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqualEntries_when_differentSortOrder() {
        var a = new ChainResponse.ChainEntryResponse(
                "wf-1", "WF", "Proj", "L", 1, true, null);
        var b = new ChainResponse.ChainEntryResponse(
                "wf-1", "WF", "Proj", "L", 2, true, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqualEntries_when_differentStopOnFailure() {
        var a = new ChainResponse.ChainEntryResponse(
                "wf-1", "WF", "Proj", "L", 1, true, null);
        var b = new ChainResponse.ChainEntryResponse(
                "wf-1", "WF", "Proj", "L", 1, false, null);

        assertThat(a).isNotEqualTo(b);
    }

    // ============================================================
    // ChainEntryResponse — toString
    // ============================================================

    @Test
    void should_containEntryFieldValues_when_entryToStringCalled() {
        var entry = new ChainResponse.ChainEntryResponse(
                "wf-42", "Deploy Workflow", "Ops Project", "Deploy Step", 3, true, Map.of("env", "prod"));
        var str = entry.toString();

        assertThat(str).contains("wf-42");
        assertThat(str).contains("Deploy Workflow");
        assertThat(str).contains("Ops Project");
        assertThat(str).contains("Deploy Step");
    }

    // ============================================================
    // ChainResponse — Constructor & accessors
    // ============================================================

    @Test
    void should_setAllFields_when_constructed() {
        var entry = new ChainResponse.ChainEntryResponse(
                "wf-1", "WF One", "Proj", "First", 0, false, Map.of());
        var meta = Map.<String, Object>of("version", 2);
        var response = new ChainResponse(
                "chain-1", "My Chain", "A chain", "🔗",
                List.of(entry), true, 42L, meta, NOW, LATER);

        assertThat(response.id()).isEqualTo("chain-1");
        assertThat(response.name()).isEqualTo("My Chain");
        assertThat(response.description()).isEqualTo("A chain");
        assertThat(response.icon()).isEqualTo("🔗");
        assertThat(response.entries()).containsExactly(entry);
        assertThat(response.stopOnFailure()).isTrue();
        assertThat(response.runCount()).isEqualTo(42L);
        assertThat(response.metadata()).containsEntry("version", 2);
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.updatedAt()).isEqualTo(LATER);
    }

    @Test
    void should_acceptNullFields_when_constructed() {
        var response = new ChainResponse(
                null, null, null, null, null, false, 0L, null, null, null);

        assertThat(response.id()).isNull();
        assertThat(response.name()).isNull();
        assertThat(response.description()).isNull();
        assertThat(response.icon()).isNull();
        assertThat(response.entries()).isNull();
        assertThat(response.stopOnFailure()).isFalse();
        assertThat(response.runCount()).isZero();
        assertThat(response.metadata()).isNull();
        assertThat(response.createdAt()).isNull();
        assertThat(response.updatedAt()).isNull();
    }

    @Test
    void should_acceptEmptyEntries_when_constructed() {
        var response = new ChainResponse(
                "c-1", "Chain", null, null, List.of(), false, 0L, Map.of(), NOW, null);

        assertThat(response.entries()).isEmpty();
        assertThat(response.metadata()).isEmpty();
    }

    @Test
    void should_preserveMultipleEntries_when_constructed() {
        var e1 = new ChainResponse.ChainEntryResponse(
                "wf-1", "WF1", "P1", "First", 0, false, null);
        var e2 = new ChainResponse.ChainEntryResponse(
                "wf-2", "WF2", "P2", "Second", 1, true, Map.of("x", 1));
        var response = new ChainResponse(
                "c-1", "Chain", null, null, List.of(e1, e2), true, 5L, null, NOW, LATER);

        assertThat(response.entries()).hasSize(2);
        assertThat(response.entries().get(0).workflowId()).isEqualTo("wf-1");
        assertThat(response.entries().get(1).workflowId()).isEqualTo("wf-2");
        assertThat(response.entries().get(1).sortOrder()).isEqualTo(1);
    }

    // ============================================================
    // ChainResponse — Equality & hashCode
    // ============================================================

    @Test
    void should_beEqual_when_sameFieldValues() {
        var entry = new ChainResponse.ChainEntryResponse(
                "wf-1", "WF", "P", "L", 0, false, null);
        var a = new ChainResponse("c-1", "Chain", "desc", "icon",
                List.of(entry), true, 10L, Map.of("k", "v"), NOW, LATER);
        var b = new ChainResponse("c-1", "Chain", "desc", "icon",
                List.of(entry), true, 10L, Map.of("k", "v"), NOW, LATER);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        var a = new ChainResponse("c-1", "Chain", null, null, null, false, 0L, null, NOW, null);
        var b = new ChainResponse("c-2", "Chain", null, null, null, false, 0L, null, NOW, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentName() {
        var a = new ChainResponse("c-1", "Alpha", null, null, null, false, 0L, null, null, null);
        var b = new ChainResponse("c-1", "Beta", null, null, null, false, 0L, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentStopOnFailure() {
        var a = new ChainResponse("c-1", "C", null, null, null, true, 0L, null, null, null);
        var b = new ChainResponse("c-1", "C", null, null, null, false, 0L, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentRunCount() {
        var a = new ChainResponse("c-1", "C", null, null, null, false, 1L, null, null, null);
        var b = new ChainResponse("c-1", "C", null, null, null, false, 2L, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_bothAllNulls() {
        var a = new ChainResponse(null, null, null, null, null, false, 0L, null, null, null);
        var b = new ChainResponse(null, null, null, null, null, false, 0L, null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // ============================================================
    // ChainResponse — toString
    // ============================================================

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var response = new ChainResponse(
                "c-99", "Deploy Chain", "Deploys everything", "🚀",
                List.of(), true, 7L, Map.of(), NOW, LATER);
        var str = response.toString();

        assertThat(str).contains("c-99");
        assertThat(str).contains("Deploy Chain");
        assertThat(str).contains("Deploys everything");
    }
}
