package com.automation.service.integration.providers.gitea;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GiteaLabelsTest {

    private static final String REPO = "https://g.example/api/v1/repos/o/r";

    private final GiteaHttpClient http = mock(GiteaHttpClient.class);

    private static Map<String, Object> label(long id, String name) {
        return Map.of("id", id, "name", name);
    }

    private void page(int page, List<?> labels) {
        when(http.get(eq(REPO + "/labels?limit=" + GiteaParams.MAX_LIMIT + "&page=" + page), eq("tok"), eq(Object.class)))
                .thenReturn(labels);
    }

    @Test
    void numericEntriesPassThrough_withoutFetchingLabels() {
        List<Long> ids = GiteaLabels.resolve(http, REPO, List.of("12", "7"), "tok", true);

        assertThat(ids).containsExactly(12L, 7L);
        verify(http, never()).get(anyString(), any(), any());
    }

    @Test
    void namesResolveCaseInsensitively_andKeepNumericOrder() {
        page(1, List.of(label(1, "Bug"), label(2, "Feature")));

        List<Long> ids = GiteaLabels.resolve(http, REPO, List.of("5", "bug", "FEATURE"), "tok", true);

        assertThat(ids).containsExactly(5L, 1L, 2L);
    }

    @Test
    void unknownNames_failWhenStrict_butAreDroppedOtherwise() {
        page(1, List.of(label(1, "Bug")));

        assertThatThrownBy(() -> GiteaLabels.resolve(http, REPO, List.of("bug", "nope", "x"), "tok", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope, x");
        assertThat(GiteaLabels.resolve(http, REPO, List.of("bug", "nope"), "tok", false)).containsExactly(1L);
    }

    @Test
    void fullPage_triggersNextPage_andShortPageStops() {
        List<Object> full = new ArrayList<>();
        for (int i = 1; i <= GiteaParams.MAX_LIMIT; i++) full.add(label(i, "l" + i));
        page(1, full);
        page(2, List.of(label(999, "late")));

        assertThat(GiteaLabels.resolve(http, REPO, List.of("late"), "tok", true)).containsExactly(999L);
        verify(http, times(2)).get(anyString(), eq("tok"), eq(Object.class));
    }

    @Test
    void malformedEntriesAreSkipped_andNonListEndsPaging() {
        page(1, List.of(Map.of("name", "noid"), Map.of("id", 3), "junk", label(4, "ok")));

        assertThat(GiteaLabels.resolve(http, REPO, List.of("ok", "noid"), "tok", false)).containsExactly(4L);

        when(http.get(anyString(), eq("tok"), eq(Object.class))).thenReturn(Map.of("message", "x"));
        assertThat(GiteaLabels.resolve(http, REPO, List.of("ok"), "tok", false)).isEmpty();
    }
}
