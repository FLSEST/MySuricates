package com.automation.dto.response;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowTemplateResponseTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:30:00Z");
    private static final Instant LATER = Instant.parse("2026-01-15T12:00:00Z");

    // ============================================================
    // Constructor & accessors — WorkflowTemplateResponse
    // ============================================================

    @Test
    void should_setAllFields_when_constructed() {
        var name = Map.of("de", "Vorlage", "en", "Template");
        var description = Map.of("de", "Beschreibung", "en", "Description");
        var block = BlockDefinition.builder().type("simple").build();
        var verification = new WorkflowTemplateResponse.TemplateVerificationInfo(
                "proj-1", "My Project", "run-1", NOW);

        var response = new WorkflowTemplateResponse(
                "tpl-1", name, description, "rocket", "automation",
                List.of(block), "Do this", "claude-sonnet-4-6",
                true, 5, "VERIFIED",
                List.of(verification), "1.0.0", NOW, LATER);

        assertThat(response.id()).isEqualTo("tpl-1");
        assertThat(response.name()).containsEntry("de", "Vorlage").containsEntry("en", "Template");
        assertThat(response.description()).containsEntry("de", "Beschreibung").containsEntry("en", "Description");
        assertThat(response.icon()).isEqualTo("rocket");
        assertThat(response.category()).isEqualTo("automation");
        assertThat(response.blocks()).hasSize(1);
        assertThat(response.blocks().getFirst().getType()).isEqualTo("simple");
        assertThat(response.instructions()).isEqualTo("Do this");
        assertThat(response.model()).isEqualTo("claude-sonnet-4-6");
        assertThat(response.builtIn()).isTrue();
        assertThat(response.sortOrder()).isEqualTo(5);
        assertThat(response.qualityStatus()).isEqualTo("VERIFIED");
        assertThat(response.verifications()).hasSize(1);
        assertThat(response.version()).isEqualTo("1.0.0");
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.updatedAt()).isEqualTo(LATER);
    }

    @Test
    void should_returnFalseBuiltIn_when_constructedWithFalse() {
        var response = new WorkflowTemplateResponse(
                "tpl-1", null, null, null, null,
                null, null, null, false, 0, null,
                null, null, null, null);

        assertThat(response.builtIn()).isFalse();
    }

    @Test
    void should_acceptAllNulls_when_constructed() {
        var response = new WorkflowTemplateResponse(
                null, null, null, null, null,
                null, null, null, false, 0, null,
                null, null, null, null);

        assertThat(response.id()).isNull();
        assertThat(response.name()).isNull();
        assertThat(response.description()).isNull();
        assertThat(response.icon()).isNull();
        assertThat(response.category()).isNull();
        assertThat(response.blocks()).isNull();
        assertThat(response.instructions()).isNull();
        assertThat(response.model()).isNull();
        assertThat(response.builtIn()).isFalse();
        assertThat(response.sortOrder()).isZero();
        assertThat(response.qualityStatus()).isNull();
        assertThat(response.verifications()).isNull();
        assertThat(response.version()).isNull();
        assertThat(response.createdAt()).isNull();
        assertThat(response.updatedAt()).isNull();
    }

    @Test
    void should_acceptEmptyLists_when_constructed() {
        var response = new WorkflowTemplateResponse(
                "tpl-1", Map.of(), Map.of(), "icon", "cat",
                List.of(), "instr", "model",
                true, 1, "OK",
                List.of(), "1.0", NOW, LATER);

        assertThat(response.name()).isEmpty();
        assertThat(response.description()).isEmpty();
        assertThat(response.blocks()).isEmpty();
        assertThat(response.verifications()).isEmpty();
    }

    @Test
    void should_preserveSortOrder_when_constructedWithNonZero() {
        var response = new WorkflowTemplateResponse(
                null, null, null, null, null,
                null, null, null, false, 42, null,
                null, null, null, null);

        assertThat(response.sortOrder()).isEqualTo(42);
    }

    // ============================================================
    // Constructor & accessors — TemplateVerificationInfo
    // ============================================================

    @Test
    void should_setAllFields_when_verificationInfoConstructed() {
        var info = new WorkflowTemplateResponse.TemplateVerificationInfo(
                "proj-1", "Test Project", "run-99", NOW);

        assertThat(info.projectId()).isEqualTo("proj-1");
        assertThat(info.projectName()).isEqualTo("Test Project");
        assertThat(info.lastRunId()).isEqualTo("run-99");
        assertThat(info.verifiedAt()).isEqualTo(NOW);
    }

    @Test
    void should_acceptAllNulls_when_verificationInfoConstructed() {
        var info = new WorkflowTemplateResponse.TemplateVerificationInfo(
                null, null, null, null);

        assertThat(info.projectId()).isNull();
        assertThat(info.projectName()).isNull();
        assertThat(info.lastRunId()).isNull();
        assertThat(info.verifiedAt()).isNull();
    }

    // ============================================================
    // Equality & hashCode — WorkflowTemplateResponse
    // ============================================================

    @Test
    void should_beEqual_when_sameFieldValues() {
        var name = Map.of("en", "Template");
        var desc = Map.of("en", "Desc");
        var block = BlockDefinition.builder().type("shell").build();
        var verif = new WorkflowTemplateResponse.TemplateVerificationInfo(
                "p1", "Proj", "r1", NOW);

        var a = new WorkflowTemplateResponse(
                "tpl-1", name, desc, "star", "ops",
                List.of(block), "instr", "opus",
                true, 3, "VERIFIED",
                List.of(verif), "2.0", NOW, LATER);
        var b = new WorkflowTemplateResponse(
                "tpl-1", name, desc, "star", "ops",
                List.of(block), "instr", "opus",
                true, 3, "VERIFIED",
                List.of(verif), "2.0", NOW, LATER);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        var a = new WorkflowTemplateResponse(
                "tpl-1", null, null, null, null,
                null, null, null, false, 0, null,
                null, null, null, null);
        var b = new WorkflowTemplateResponse(
                "tpl-2", null, null, null, null,
                null, null, null, false, 0, null,
                null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentBuiltIn() {
        var a = new WorkflowTemplateResponse(
                "tpl-1", null, null, null, null,
                null, null, null, true, 0, null,
                null, null, null, null);
        var b = new WorkflowTemplateResponse(
                "tpl-1", null, null, null, null,
                null, null, null, false, 0, null,
                null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentSortOrder() {
        var a = new WorkflowTemplateResponse(
                "tpl-1", null, null, null, null,
                null, null, null, false, 1, null,
                null, null, null, null);
        var b = new WorkflowTemplateResponse(
                "tpl-1", null, null, null, null,
                null, null, null, false, 2, null,
                null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentCategory() {
        var a = new WorkflowTemplateResponse(
                "tpl-1", null, null, null, "ops",
                null, null, null, false, 0, null,
                null, null, null, null);
        var b = new WorkflowTemplateResponse(
                "tpl-1", null, null, null, "dev",
                null, null, null, false, 0, null,
                null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentModel() {
        var a = new WorkflowTemplateResponse(
                "tpl-1", null, null, null, null,
                null, null, "opus", false, 0, null,
                null, null, null, null);
        var b = new WorkflowTemplateResponse(
                "tpl-1", null, null, null, null,
                null, null, "sonnet", false, 0, null,
                null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var response = new WorkflowTemplateResponse(
                "tpl-1", null, null, null, null,
                null, null, null, false, 0, null,
                null, null, null, null);

        assertThat(response).isNotEqualTo(null);
    }

    @Test
    void should_beEqual_when_bothAllNulls() {
        var a = new WorkflowTemplateResponse(
                null, null, null, null, null,
                null, null, null, false, 0, null,
                null, null, null, null);
        var b = new WorkflowTemplateResponse(
                null, null, null, null, null,
                null, null, null, false, 0, null,
                null, null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // ============================================================
    // Equality & hashCode — TemplateVerificationInfo
    // ============================================================

    @Test
    void should_beEqual_when_verificationInfoSameValues() {
        var a = new WorkflowTemplateResponse.TemplateVerificationInfo(
                "proj-1", "Project", "run-1", NOW);
        var b = new WorkflowTemplateResponse.TemplateVerificationInfo(
                "proj-1", "Project", "run-1", NOW);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_verificationInfoDifferentProjectId() {
        var a = new WorkflowTemplateResponse.TemplateVerificationInfo(
                "proj-1", "Project", "run-1", NOW);
        var b = new WorkflowTemplateResponse.TemplateVerificationInfo(
                "proj-2", "Project", "run-1", NOW);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_verificationInfoDifferentRunId() {
        var a = new WorkflowTemplateResponse.TemplateVerificationInfo(
                "proj-1", "Project", "run-1", NOW);
        var b = new WorkflowTemplateResponse.TemplateVerificationInfo(
                "proj-1", "Project", "run-2", NOW);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_verificationInfoBothNull() {
        var a = new WorkflowTemplateResponse.TemplateVerificationInfo(
                null, null, null, null);
        var b = new WorkflowTemplateResponse.TemplateVerificationInfo(
                null, null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // ============================================================
    // toString
    // ============================================================

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var response = new WorkflowTemplateResponse(
                "tpl-42", Map.of("en", "My Template"), Map.of("en", "My Desc"),
                "rocket", "automation",
                List.of(), "instructions here", "claude-opus-4-6",
                true, 10, "VERIFIED",
                List.of(), "3.0.0", NOW, LATER);
        var str = response.toString();

        assertThat(str).contains("tpl-42");
        assertThat(str).contains("My Template");
        assertThat(str).contains("rocket");
        assertThat(str).contains("automation");
        assertThat(str).contains("claude-opus-4-6");
        assertThat(str).contains("VERIFIED");
        assertThat(str).contains("3.0.0");
    }

    @Test
    void should_handleNullsInToString_when_fieldsAreNull() {
        var response = new WorkflowTemplateResponse(
                null, null, null, null, null,
                null, null, null, false, 0, null,
                null, null, null, null);
        var str = response.toString();

        assertThat(str).contains("null");
    }

    @Test
    void should_containFieldValues_when_verificationInfoToStringCalled() {
        var info = new WorkflowTemplateResponse.TemplateVerificationInfo(
                "proj-99", "Mega Project", "run-42", NOW);
        var str = info.toString();

        assertThat(str).contains("proj-99");
        assertThat(str).contains("Mega Project");
        assertThat(str).contains("run-42");
    }
}
