package com.automation.service.integration.providers.github;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubParamsTest {

    @Test
    void extractToken_takesPartAfterPipe_orWholeValue() {
        assertThat(GitHubParams.extractToken("flo|ghp_x")).isEqualTo("ghp_x");
        assertThat(GitHubParams.extractToken("ghp_x")).isEqualTo("ghp_x");
        assertThat(GitHubParams.extractToken(null)).isNull();
    }

    @Test
    void splitRepo_requiresOwnerSlashRepo() {
        assertThat(GitHubParams.splitRepo("FLSEST/MySuricates")).containsExactly("FLSEST", "MySuricates");
        assertThatThrownBy(() -> GitHubParams.splitRepo("nur-repo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Parameter 'repo' must be in the form 'owner/repo'");
    }

    @Test
    void csv_trimsAndDropsEmptyEntries() {
        assertThat(GitHubParams.csv(" a, b ,,c ")).isEqualTo(List.of("a", "b", "c"));
    }

    @Test
    void paramInt_acceptsNumbersAndNumericStrings_elseDefault() {
        assertThat(GitHubParams.paramInt(Map.of("page", 3), "page", 1)).isEqualTo(3);
        assertThat(GitHubParams.paramInt(Map.of("page", "7"), "page", 1)).isEqualTo(7);
        assertThat(GitHubParams.paramInt(Map.of("page", " "), "page", 1)).isEqualTo(1);
        assertThat(GitHubParams.paramInt(Map.of(), "page", 1)).isEqualTo(1);
    }

    @Test
    void requireParam_missing_throwsWithKey() {
        assertThatThrownBy(() -> GitHubParams.requireParam(Map.of(), "number"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Required parameter missing: number");
    }

    @Test
    void urlEncode_encodesSlashAndSpace() {
        assertThat(GitHubParams.urlEncode("a b/c")).isEqualTo("a+b%2Fc");
    }
}
