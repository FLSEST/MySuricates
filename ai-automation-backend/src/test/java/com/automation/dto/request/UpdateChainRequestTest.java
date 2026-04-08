package com.automation.dto.request;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateChainRequestTest {

    private static ChainEntryRequest sampleEntry(String workflowId) {
        return new ChainEntryRequest(workflowId, "label-" + workflowId, 1, false, Map.of());
    }

    @Test
    void should_returnName_when_constructedWithName() {
        var request = new UpdateChainRequest("My Chain", null, null, null, null);

        assertThat(request.name()).isEqualTo("My Chain");
    }

    @Test
    void should_returnDescription_when_constructedWithDescription() {
        var request = new UpdateChainRequest(null, "A useful chain", null, null, null);

        assertThat(request.description()).isEqualTo("A useful chain");
    }

    @Test
    void should_returnIcon_when_constructedWithIcon() {
        var request = new UpdateChainRequest(null, null, "rocket", null, null);

        assertThat(request.icon()).isEqualTo("rocket");
    }

    @Test
    void should_returnEntries_when_constructedWithPopulatedList() {
        var entries = List.of(sampleEntry("w1"), sampleEntry("w2"));
        var request = new UpdateChainRequest(null, null, null, entries, null);

        assertThat(request.entries()).hasSize(2);
        assertThat(request.entries().get(0).workflowId()).isEqualTo("w1");
        assertThat(request.entries().get(1).workflowId()).isEqualTo("w2");
    }

    @Test
    void should_returnStopOnFailure_when_constructedWithTrue() {
        var request = new UpdateChainRequest(null, null, null, null, true);

        assertThat(request.stopOnFailure()).isTrue();
    }

    @Test
    void should_returnStopOnFailure_when_constructedWithFalse() {
        var request = new UpdateChainRequest(null, null, null, null, false);

        assertThat(request.stopOnFailure()).isFalse();
    }

    @Test
    void should_returnAllFields_when_fullyPopulated() {
        var entries = List.of(sampleEntry("w1"));
        var request = new UpdateChainRequest("Chain", "Desc", "star", entries, true);

        assertThat(request.name()).isEqualTo("Chain");
        assertThat(request.description()).isEqualTo("Desc");
        assertThat(request.icon()).isEqualTo("star");
        assertThat(request.entries()).containsExactlyElementsOf(entries);
        assertThat(request.stopOnFailure()).isTrue();
    }

    @Test
    void should_returnEmptyList_when_entriesIsEmpty() {
        var request = new UpdateChainRequest("Chain", null, null, List.of(), null);

        assertThat(request.entries()).isEmpty();
    }

    @Test
    void should_returnNull_when_entriesIsNull() {
        var request = new UpdateChainRequest("Chain", null, null, null, null);

        assertThat(request.entries()).isNull();
    }

    @Test
    void should_returnNullForAllFields_when_constructedWithAllNulls() {
        var request = new UpdateChainRequest(null, null, null, null, null);

        assertThat(request.name()).isNull();
        assertThat(request.description()).isNull();
        assertThat(request.icon()).isNull();
        assertThat(request.entries()).isNull();
        assertThat(request.stopOnFailure()).isNull();
    }

    @Test
    void should_beEqual_when_allFieldsMatch() {
        var entries = List.of(sampleEntry("w1"));
        var a = new UpdateChainRequest("Chain", "Desc", "star", entries, true);
        var b = new UpdateChainRequest("Chain", "Desc", "star", entries, true);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveEmptyEntries() {
        var a = new UpdateChainRequest("Chain", null, null, List.of(), null);
        var b = new UpdateChainRequest("Chain", null, null, Collections.emptyList(), null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveNullFields() {
        var a = new UpdateChainRequest(null, null, null, null, null);
        var b = new UpdateChainRequest(null, null, null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_nameDiffers() {
        var a = new UpdateChainRequest("Chain A", "Desc", "star", null, true);
        var b = new UpdateChainRequest("Chain B", "Desc", "star", null, true);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_descriptionDiffers() {
        var a = new UpdateChainRequest("Chain", "Desc A", null, null, null);
        var b = new UpdateChainRequest("Chain", "Desc B", null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_entriesDiffer() {
        var a = new UpdateChainRequest("Chain", null, null, List.of(sampleEntry("w1")), null);
        var b = new UpdateChainRequest("Chain", null, null, List.of(sampleEntry("w2")), null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_stopOnFailureDiffers() {
        var a = new UpdateChainRequest("Chain", null, null, null, true);
        var b = new UpdateChainRequest("Chain", null, null, null, false);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var request = new UpdateChainRequest("Chain", "Desc", "star", List.of(), true);

        assertThat(request).isNotEqualTo(null);
    }

    @Test
    void should_containFieldNamesInToString() {
        var request = new UpdateChainRequest("Chain", "Desc", "star", List.of(), true);

        assertThat(request.toString()).contains("name");
        assertThat(request.toString()).contains("description");
        assertThat(request.toString()).contains("icon");
        assertThat(request.toString()).contains("entries");
        assertThat(request.toString()).contains("stopOnFailure");
    }

    @Test
    void should_containValuesInToString() {
        var request = new UpdateChainRequest("Chain", "Desc", "star", List.of(), true);

        assertThat(request.toString()).contains("Chain");
        assertThat(request.toString()).contains("Desc");
        assertThat(request.toString()).contains("star");
    }
}
