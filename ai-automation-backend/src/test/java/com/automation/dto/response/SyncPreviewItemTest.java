package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SyncPreviewItemTest {

    private static final WorkflowDiff SAMPLE_DIFF = new WorkflowDiff(List.of("model", "name"), "sonnet", "opus", 2, 1);

    // --- Primary constructor & accessors ---

    @Test
    void should_setAllFields_when_constructedWithDiff() {
        var item = new SyncPreviewItem("id-1", "Workflow A", "Project A", "CREATE", SAMPLE_DIFF);

        assertThat(item.id()).isEqualTo("id-1");
        assertThat(item.name()).isEqualTo("Workflow A");
        assertThat(item.projectName()).isEqualTo("Project A");
        assertThat(item.action()).isEqualTo("CREATE");
        assertThat(item.diff()).isEqualTo(SAMPLE_DIFF);
    }

    @Test
    void should_setDiffToNull_when_constructedWithConvenienceConstructor() {
        var item = new SyncPreviewItem("id-2", "Workflow B", "Project B", "UPDATE");

        assertThat(item.id()).isEqualTo("id-2");
        assertThat(item.name()).isEqualTo("Workflow B");
        assertThat(item.projectName()).isEqualTo("Project B");
        assertThat(item.action()).isEqualTo("UPDATE");
        assertThat(item.diff()).isNull();
    }

    @Test
    void should_acceptNullProjectName_when_constructed() {
        var item = new SyncPreviewItem("id-3", "Project X", null, "CREATE", SAMPLE_DIFF);

        assertThat(item.projectName()).isNull();
        assertThat(item.id()).isEqualTo("id-3");
        assertThat(item.diff()).isEqualTo(SAMPLE_DIFF);
    }

    @Test
    void should_acceptNullDiff_when_constructedWithPrimaryConstructor() {
        var item = new SyncPreviewItem("id-4", "Skill A", "Project A", "DELETE", null);

        assertThat(item.diff()).isNull();
        assertThat(item.action()).isEqualTo("DELETE");
    }

    @Test
    void should_acceptNullProjectNameAndNullDiff_when_constructed() {
        var item = new SyncPreviewItem("id-5", "Item", null, "CREATE", null);

        assertThat(item.projectName()).isNull();
        assertThat(item.diff()).isNull();
        assertThat(item.id()).isEqualTo("id-5");
        assertThat(item.name()).isEqualTo("Item");
    }

    @Test
    void should_acceptNullId_when_constructed() {
        var item = new SyncPreviewItem(null, "Name", "Project", "CREATE", SAMPLE_DIFF);

        assertThat(item.id()).isNull();
    }

    @Test
    void should_acceptNullName_when_constructed() {
        var item = new SyncPreviewItem("id-6", null, "Project", "UPDATE", SAMPLE_DIFF);

        assertThat(item.name()).isNull();
    }

    @Test
    void should_acceptNullAction_when_constructed() {
        var item = new SyncPreviewItem("id-7", "Name", "Project", null, SAMPLE_DIFF);

        assertThat(item.action()).isNull();
    }

    @Test
    void should_acceptAllNulls_when_constructed() {
        var item = new SyncPreviewItem(null, null, null, null, null);

        assertThat(item.id()).isNull();
        assertThat(item.name()).isNull();
        assertThat(item.projectName()).isNull();
        assertThat(item.action()).isNull();
        assertThat(item.diff()).isNull();
    }

    // --- Convenience constructor equivalence ---

    @Test
    void should_beEquivalentToPrimaryWithNullDiff_when_convenienceConstructorUsed() {
        var fromConvenience = new SyncPreviewItem("id-8", "WF", "Proj", "CREATE");
        var fromPrimary = new SyncPreviewItem("id-8", "WF", "Proj", "CREATE", null);

        assertThat(fromConvenience).isEqualTo(fromPrimary);
        assertThat(fromConvenience.hashCode()).isEqualTo(fromPrimary.hashCode());
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var a = new SyncPreviewItem("id-1", "Name", "Project", "CREATE", SAMPLE_DIFF);
        var b = new SyncPreviewItem("id-1", "Name", "Project", "CREATE", SAMPLE_DIFF);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothAllNulls() {
        var a = new SyncPreviewItem(null, null, null, null, null);
        var b = new SyncPreviewItem(null, null, null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        var a = new SyncPreviewItem("id-1", "Name", "Project", "CREATE", SAMPLE_DIFF);
        var b = new SyncPreviewItem("id-2", "Name", "Project", "CREATE", SAMPLE_DIFF);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentName() {
        var a = new SyncPreviewItem("id-1", "Name A", "Project", "CREATE", SAMPLE_DIFF);
        var b = new SyncPreviewItem("id-1", "Name B", "Project", "CREATE", SAMPLE_DIFF);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentProjectName() {
        var a = new SyncPreviewItem("id-1", "Name", "Project A", "CREATE", SAMPLE_DIFF);
        var b = new SyncPreviewItem("id-1", "Name", "Project B", "CREATE", SAMPLE_DIFF);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentAction() {
        var a = new SyncPreviewItem("id-1", "Name", "Project", "CREATE", SAMPLE_DIFF);
        var b = new SyncPreviewItem("id-1", "Name", "Project", "DELETE", SAMPLE_DIFF);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentDiff() {
        var otherDiff = new WorkflowDiff(List.of("blocks"), "haiku", "opus", 0, 0);
        var a = new SyncPreviewItem("id-1", "Name", "Project", "CREATE", SAMPLE_DIFF);
        var b = new SyncPreviewItem("id-1", "Name", "Project", "CREATE", otherDiff);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_diffNullVsNonNull() {
        var a = new SyncPreviewItem("id-1", "Name", "Project", "CREATE", null);
        var b = new SyncPreviewItem("id-1", "Name", "Project", "CREATE", SAMPLE_DIFF);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var item = new SyncPreviewItem("id-1", "Name", "Project", "CREATE", SAMPLE_DIFF);

        assertThat(item).isNotEqualTo(null);
    }

    // --- toString ---

    @Test
    void should_containFieldNames_when_toStringCalled() {
        var item = new SyncPreviewItem("id-1", "Workflow A", "Project A", "CREATE", SAMPLE_DIFF);
        var str = item.toString();

        assertThat(str).contains("id");
        assertThat(str).contains("name");
        assertThat(str).contains("projectName");
        assertThat(str).contains("action");
        assertThat(str).contains("diff");
    }

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var item = new SyncPreviewItem("id-1", "Workflow A", "Project A", "CREATE", SAMPLE_DIFF);
        var str = item.toString();

        assertThat(str).contains("id-1");
        assertThat(str).contains("Workflow A");
        assertThat(str).contains("Project A");
        assertThat(str).contains("CREATE");
    }

    @Test
    void should_notThrow_when_toStringCalledWithNulls() {
        var item = new SyncPreviewItem(null, null, null, null, null);
        var str = item.toString();

        assertThat(str).isNotNull();
    }

    @Test
    void should_notThrow_when_toStringCalledWithConvenienceConstructor() {
        var item = new SyncPreviewItem("id-1", "Name", null, "CREATE");
        var str = item.toString();

        assertThat(str).isNotNull();
        assertThat(str).contains("id-1");
    }
}
