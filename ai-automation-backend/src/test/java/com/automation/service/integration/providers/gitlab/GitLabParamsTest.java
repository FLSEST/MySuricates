package com.automation.service.integration.providers.gitlab;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitLabParamsTest {

    @Test
    void projectRef_encodesPathsAndKeepsNumericIds() {
        assertThat(GitLabParams.projectRef("grp/app")).isEqualTo("grp%2Fapp");
        assertThat(GitLabParams.projectRef(" grp/sub-1/my.app_x ")).isEqualTo("grp%2Fsub-1%2Fmy.app_x");
        assertThat(GitLabParams.projectRef("4711")).isEqualTo("4711");
    }

    @Test
    void projectRef_rejectsSingleSegmentsTraversalAndOddCharacters() {
        for (String bad : new String[]{"", "app", "grp/", "/app", "grp/../x", "grp/./x", "grp/a b", "grp/a?x=1", "grp/a#b", null}) {
            assertThatThrownBy(() -> GitLabParams.projectRef(bad))
                    .as("'%s'", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'project'");
        }
    }

    @Test
    void listState_mapsToGitLabValues() {
        assertThat(GitLabParams.listState("open")).isEqualTo("opened");
        assertThat(GitLabParams.listState("opened")).isEqualTo("opened");
        assertThat(GitLabParams.listState("Closed")).isEqualTo("closed");
        assertThat(GitLabParams.listState("all")).isNull();
        assertThatThrownBy(() -> GitLabParams.listState("merged")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stateEvent_mapsOpenAndClosed() {
        assertThat(GitLabParams.stateEvent("open")).isEqualTo("reopen");
        assertThat(GitLabParams.stateEvent("reopen")).isEqualTo("reopen");
        assertThat(GitLabParams.stateEvent("closed")).isEqualTo("close");
        assertThat(GitLabParams.stateEvent("close")).isEqualTo("close");
        assertThatThrownBy(() -> GitLabParams.stateEvent("done")).hasMessageContaining("open or closed");
    }

    @Test
    void ids_acceptsNumericUserIdsOnly() {
        assertThat(GitLabParams.ids("3, 12", "assigneeIds")).containsExactly(3L, 12L);
        assertThat(GitLabParams.ids(List.of(5), "assigneeIds")).containsExactly(5L);
        assertThatThrownBy(() -> GitLabParams.ids("alex", "assigneeIds"))
                .hasMessage("Parameter 'assigneeIds' expects numeric user IDs");
    }

    @Test
    void perPage_isClampedTo100() {
        assertThat(GitLabParams.perPage(Map.of("perPage", 1000), 30)).isEqualTo(100);
        assertThat(GitLabParams.perPage(Map.of("perPage", -3), 30)).isEqualTo(1);
        assertThat(GitLabParams.perPage(Map.of(), 30)).isEqualTo(30);
    }

    @Test
    void requireIid_acceptsDigitsOnly() {
        assertThat(GitLabParams.requireIid(Map.of("number", 8), "number")).isEqualTo("8");
        assertThatThrownBy(() -> GitLabParams.requireIid(Map.of("number", "8/notes"), "number"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GitLabParams.requireIid(Map.of(), "number"))
                .hasMessage("Required parameter missing: number");
    }

    /** ac639b39: a JSON number is read like its text — 1.5 is not cut to 1, nothing wraps around. */
    @Test
    void paramInt_rejectsDecimalsAndValuesBeyondInt() {
        for (Object bad : new Object[] {1.5, 5.0, 3_000_000_000L}) {
            assertThatThrownBy(() -> GitLabParams.paramInt(Map.of("page", bad), "page", 1))
                    .as(String.valueOf(bad))
                    .hasMessage("Parameter 'page' must be a number");
        }
    }

    /** ac639b39: 8.0 used to pass as IID 8, and 2^64 + 1 as IID 1. */
    @Test
    void requireIid_readsAJsonNumberLikeItsText() {
        assertThatThrownBy(() -> GitLabParams.requireIid(Map.of("number", 8.0), "number"))
                .hasMessage("Parameter 'number' must be a positive number");
        BigInteger huge = BigInteger.ONE.shiftLeft(64).add(BigInteger.ONE);
        assertThat(GitLabParams.requireIid(Map.of("number", huge), "number")).isEqualTo(huge.toString());
    }
}
