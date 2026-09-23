package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CalendarDataResponseTest {

    // --- Constructor & accessors ---

    @Test
    void should_returnEmptyLists_when_constructedWithEmptyLists() {
        var response = new CalendarDataResponse(List.of(), List.of());

        assertThat(response.upcoming()).isEmpty();
        assertThat(response.pastRuns()).isEmpty();
    }

    @Test
    void should_returnUpcomingList_when_constructedWithUpcoming() {
        var scheduled = mock(ScheduledWorkflowResponse.class);
        var response = new CalendarDataResponse(List.of(scheduled), List.of());

        assertThat(response.upcoming()).containsExactly(scheduled);
        assertThat(response.pastRuns()).isEmpty();
    }

    @Test
    void should_returnPastRunsList_when_constructedWithPastRuns() {
        var run = mock(WorkflowRunListItemResponse.class);
        var response = new CalendarDataResponse(List.of(), List.of(run));

        assertThat(response.upcoming()).isEmpty();
        assertThat(response.pastRuns()).containsExactly(run);
    }

    @Test
    void should_returnBothLists_when_constructedWithBothPopulated() {
        var scheduled1 = mock(ScheduledWorkflowResponse.class);
        var scheduled2 = mock(ScheduledWorkflowResponse.class);
        var run1 = mock(WorkflowRunListItemResponse.class);
        var run2 = mock(WorkflowRunListItemResponse.class);
        var run3 = mock(WorkflowRunListItemResponse.class);

        var response = new CalendarDataResponse(
                List.of(scheduled1, scheduled2),
                List.of(run1, run2, run3)
        );

        assertThat(response.upcoming()).hasSize(2).containsExactly(scheduled1, scheduled2);
        assertThat(response.pastRuns()).hasSize(3).containsExactly(run1, run2, run3);
    }

    @Test
    void should_acceptNullUpcoming_when_constructed() {
        var run = mock(WorkflowRunListItemResponse.class);
        var response = new CalendarDataResponse(null, List.of(run));

        assertThat(response.upcoming()).isNull();
        assertThat(response.pastRuns()).containsExactly(run);
    }

    @Test
    void should_acceptNullPastRuns_when_constructed() {
        var scheduled = mock(ScheduledWorkflowResponse.class);
        var response = new CalendarDataResponse(List.of(scheduled), null);

        assertThat(response.upcoming()).containsExactly(scheduled);
        assertThat(response.pastRuns()).isNull();
    }

    @Test
    void should_acceptBothNull_when_constructed() {
        var response = new CalendarDataResponse(null, null);

        assertThat(response.upcoming()).isNull();
        assertThat(response.pastRuns()).isNull();
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_bothHaveEmptyLists() {
        var a = new CalendarDataResponse(List.of(), List.of());
        var b = new CalendarDataResponse(List.of(), List.of());

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveSameContent() {
        var scheduled = mock(ScheduledWorkflowResponse.class);
        var run = mock(WorkflowRunListItemResponse.class);

        var a = new CalendarDataResponse(List.of(scheduled), List.of(run));
        var b = new CalendarDataResponse(List.of(scheduled), List.of(run));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothNull() {
        var a = new CalendarDataResponse(null, null);
        var b = new CalendarDataResponse(null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentUpcoming() {
        var scheduled1 = mock(ScheduledWorkflowResponse.class);
        var scheduled2 = mock(ScheduledWorkflowResponse.class);

        var a = new CalendarDataResponse(List.of(scheduled1), List.of());
        var b = new CalendarDataResponse(List.of(scheduled2), List.of());

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentPastRuns() {
        var run1 = mock(WorkflowRunListItemResponse.class);
        var run2 = mock(WorkflowRunListItemResponse.class);

        var a = new CalendarDataResponse(List.of(), List.of(run1));
        var b = new CalendarDataResponse(List.of(), List.of(run2));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_upcomingNullVsEmpty() {
        var a = new CalendarDataResponse(null, List.of());
        var b = new CalendarDataResponse(List.of(), List.of());

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var response = new CalendarDataResponse(List.of(), List.of());

        assertThat(response).isNotEqualTo(null);
    }

    // --- toString ---

    @Test
    void should_containFieldNames_when_toStringCalled() {
        var response = new CalendarDataResponse(List.of(), List.of());
        var str = response.toString();

        assertThat(str).contains("upcoming");
        assertThat(str).contains("pastRuns");
    }

    @Test
    void should_containListContents_when_toStringCalledWithPopulatedLists() {
        var scheduled = mock(ScheduledWorkflowResponse.class);
        var run = mock(WorkflowRunListItemResponse.class);

        var response = new CalendarDataResponse(List.of(scheduled), List.of(run));
        var str = response.toString();

        assertThat(str).contains("upcoming");
        assertThat(str).contains("pastRuns");
        assertThat(str).contains(scheduled.toString());
        assertThat(str).contains(run.toString());
    }
}
