package com.automation.dto.request;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SyncDefinitionsRequestTest {

    @Test
    void should_returnProjectIds_when_constructedWithPopulatedList() {
        var request = new SyncDefinitionsRequest(List.of("p1", "p2"), List.of(), List.of());

        assertThat(request.projectIds()).containsExactly("p1", "p2");
    }

    @Test
    void should_returnWorkflowIds_when_constructedWithPopulatedList() {
        var request = new SyncDefinitionsRequest(List.of(), List.of("w1", "w2"), List.of());

        assertThat(request.workflowIds()).containsExactly("w1", "w2");
    }

    @Test
    void should_returnSkillIds_when_constructedWithPopulatedList() {
        var request = new SyncDefinitionsRequest(List.of(), List.of(), List.of("s1", "s2"));

        assertThat(request.skillIds()).containsExactly("s1", "s2");
    }

    @Test
    void should_returnEmptyLists_when_constructedWithEmptyLists() {
        var request = new SyncDefinitionsRequest(List.of(), List.of(), List.of());

        assertThat(request.projectIds()).isEmpty();
        assertThat(request.workflowIds()).isEmpty();
        assertThat(request.skillIds()).isEmpty();
    }

    @Test
    void should_returnNull_when_constructedWithNullLists() {
        var request = new SyncDefinitionsRequest(null, null, null);

        assertThat(request.projectIds()).isNull();
        assertThat(request.workflowIds()).isNull();
        assertThat(request.skillIds()).isNull();
    }

    @Test
    void should_allowMixedNullAndPopulatedLists() {
        var request = new SyncDefinitionsRequest(List.of("p1"), null, List.of("s1"));

        assertThat(request.projectIds()).containsExactly("p1");
        assertThat(request.workflowIds()).isNull();
        assertThat(request.skillIds()).containsExactly("s1");
    }

    @Test
    void should_beEqual_when_allFieldsMatch() {
        var a = new SyncDefinitionsRequest(List.of("p1"), List.of("w1"), List.of("s1"));
        var b = new SyncDefinitionsRequest(List.of("p1"), List.of("w1"), List.of("s1"));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveEmptyLists() {
        var a = new SyncDefinitionsRequest(List.of(), List.of(), List.of());
        var b = new SyncDefinitionsRequest(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_projectIdsDiffer() {
        var a = new SyncDefinitionsRequest(List.of("p1"), List.of("w1"), List.of("s1"));
        var b = new SyncDefinitionsRequest(List.of("p2"), List.of("w1"), List.of("s1"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_workflowIdsDiffer() {
        var a = new SyncDefinitionsRequest(List.of("p1"), List.of("w1"), List.of("s1"));
        var b = new SyncDefinitionsRequest(List.of("p1"), List.of("w2"), List.of("s1"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_skillIdsDiffer() {
        var a = new SyncDefinitionsRequest(List.of("p1"), List.of("w1"), List.of("s1"));
        var b = new SyncDefinitionsRequest(List.of("p1"), List.of("w1"), List.of("s2"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var request = new SyncDefinitionsRequest(List.of("p1"), List.of("w1"), List.of("s1"));

        assertThat(request).isNotEqualTo(null);
    }

    @Test
    void should_containFieldNamesInToString() {
        var request = new SyncDefinitionsRequest(List.of("p1"), List.of("w1"), List.of("s1"));

        assertThat(request.toString()).contains("projectIds");
        assertThat(request.toString()).contains("workflowIds");
        assertThat(request.toString()).contains("skillIds");
    }

    @Test
    void should_containValuesInToString() {
        var request = new SyncDefinitionsRequest(List.of("p1"), List.of("w1"), List.of("s1"));

        assertThat(request.toString()).contains("p1");
        assertThat(request.toString()).contains("w1");
        assertThat(request.toString()).contains("s1");
    }
}
