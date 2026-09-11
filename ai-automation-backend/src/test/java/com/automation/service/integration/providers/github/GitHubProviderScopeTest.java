package com.automation.service.integration.providers.github;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Scope discovery for classic PATs (X-OAuth-Scopes header) — TODO 62705e0b. */
class GitHubProviderScopeTest {

    @Test
    void parseScopeHeader_commaSeparated_becomesSpaceSeparated() {
        assertThat(GitHubProvider.parseScopeHeader("repo, read:org, workflow")).isEqualTo("repo read:org workflow");
    }

    @Test
    void parseScopeHeader_singleScope_isKept() {
        assertThat(GitHubProvider.parseScopeHeader("public_repo")).isEqualTo("public_repo");
    }

    @Test
    void parseScopeHeader_absentOrBlank_meansUnknown() {
        assertThat(GitHubProvider.parseScopeHeader(null)).isNull();
        assertThat(GitHubProvider.parseScopeHeader("")).isNull();
        assertThat(GitHubProvider.parseScopeHeader("   ")).isNull();
    }
}
