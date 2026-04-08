package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SyncPreviewTest {

    private static final SyncPreviewItem PROJECT_1 = new SyncPreviewItem("p-1", "Project A", null, "CREATE");
    private static final SyncPreviewItem PROJECT_2 = new SyncPreviewItem("p-2", "Project B", null, "UPDATE");
    private static final SyncPreviewItem WORKFLOW_1 = new SyncPreviewItem("wf-1", "Workflow A", "Project A", "CREATE");
    private static final SyncPreviewItem SKILL_1 = new SyncPreviewItem("sk-1", "Skill A", "Project A", "CREATE");

    // --- Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructedWithPopulatedLists() {
        var projects = List.of(PROJECT_1, PROJECT_2);
        var workflows = List.of(WORKFLOW_1);
        var skills = List.of(SKILL_1);

        var preview = new SyncPreview(projects, workflows, skills);

        assertThat(preview.projects()).containsExactly(PROJECT_1, PROJECT_2);
        assertThat(preview.workflows()).containsExactly(WORKFLOW_1);
        assertThat(preview.skills()).containsExactly(SKILL_1);
    }

    @Test
    void should_acceptEmptyLists_when_constructed() {
        var preview = new SyncPreview(List.of(), List.of(), List.of());

        assertThat(preview.projects()).isEmpty();
        assertThat(preview.workflows()).isEmpty();
        assertThat(preview.skills()).isEmpty();
    }

    @Test
    void should_acceptNullProjects_when_constructed() {
        var preview = new SyncPreview(null, List.of(WORKFLOW_1), List.of(SKILL_1));

        assertThat(preview.projects()).isNull();
        assertThat(preview.workflows()).containsExactly(WORKFLOW_1);
        assertThat(preview.skills()).containsExactly(SKILL_1);
    }

    @Test
    void should_acceptNullWorkflows_when_constructed() {
        var preview = new SyncPreview(List.of(PROJECT_1), null, List.of(SKILL_1));

        assertThat(preview.projects()).containsExactly(PROJECT_1);
        assertThat(preview.workflows()).isNull();
        assertThat(preview.skills()).containsExactly(SKILL_1);
    }

    @Test
    void should_acceptNullSkills_when_constructed() {
        var preview = new SyncPreview(List.of(PROJECT_1), List.of(WORKFLOW_1), null);

        assertThat(preview.projects()).containsExactly(PROJECT_1);
        assertThat(preview.workflows()).containsExactly(WORKFLOW_1);
        assertThat(preview.skills()).isNull();
    }

    @Test
    void should_acceptAllNulls_when_constructed() {
        var preview = new SyncPreview(null, null, null);

        assertThat(preview.projects()).isNull();
        assertThat(preview.workflows()).isNull();
        assertThat(preview.skills()).isNull();
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var a = new SyncPreview(List.of(PROJECT_1), List.of(WORKFLOW_1), List.of(SKILL_1));
        var b = new SyncPreview(List.of(PROJECT_1), List.of(WORKFLOW_1), List.of(SKILL_1));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentProjects() {
        var a = new SyncPreview(List.of(PROJECT_1), List.of(WORKFLOW_1), List.of(SKILL_1));
        var b = new SyncPreview(List.of(PROJECT_2), List.of(WORKFLOW_1), List.of(SKILL_1));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentWorkflows() {
        var a = new SyncPreview(List.of(PROJECT_1), List.of(WORKFLOW_1), List.of(SKILL_1));
        var b = new SyncPreview(List.of(PROJECT_1), List.of(), List.of(SKILL_1));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentSkills() {
        var a = new SyncPreview(List.of(PROJECT_1), List.of(WORKFLOW_1), List.of(SKILL_1));
        var b = new SyncPreview(List.of(PROJECT_1), List.of(WORKFLOW_1), List.of());

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_bothAllNulls() {
        var a = new SyncPreview(null, null, null);
        var b = new SyncPreview(null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothEmptyLists() {
        var a = new SyncPreview(List.of(), List.of(), List.of());
        var b = new SyncPreview(List.of(), List.of(), List.of());

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_nullVsEmptyList() {
        var a = new SyncPreview(null, List.of(), List.of());
        var b = new SyncPreview(List.of(), List.of(), List.of());

        assertThat(a).isNotEqualTo(b);
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var preview = new SyncPreview(List.of(PROJECT_1), List.of(WORKFLOW_1), List.of(SKILL_1));
        var str = preview.toString();

        assertThat(str).contains("p-1");
        assertThat(str).contains("wf-1");
        assertThat(str).contains("sk-1");
    }

    @Test
    void should_notThrow_when_toStringCalledWithNulls() {
        var preview = new SyncPreview(null, null, null);
        var str = preview.toString();

        assertThat(str).isNotNull();
    }
}
