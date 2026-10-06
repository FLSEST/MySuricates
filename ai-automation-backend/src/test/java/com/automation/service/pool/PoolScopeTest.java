package com.automation.service.pool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code scope} is the pool metadatum that separates repo-bound from portable entries
 * (docs/workflow-pool-kuratierung.md).
 */
class PoolScopeTest {

    @Test
    void defaultsToPortableWhenUnset() {
        assertThat(PoolScope.fromParam(null)).isEqualTo(PoolScope.PORTABLE);
        assertThat(PoolScope.fromParam("  ")).isEqualTo(PoolScope.PORTABLE);
    }

    @Test
    void parsesEveryValueCaseInsensitively() {
        assertThat(PoolScope.fromParam("repo")).isEqualTo(PoolScope.REPO);
        assertThat(PoolScope.fromParam("REPO")).isEqualTo(PoolScope.REPO);
        assertThat(PoolScope.fromParam("Portable")).isEqualTo(PoolScope.PORTABLE);
        assertThat(PoolScope.fromParam("external")).isEqualTo(PoolScope.EXTERNAL);
    }

    /** The message names every allowed value, so a new one cannot be forgotten there (TODO 7ddacd1d). */
    @Test
    void rejectsUnknownValue_namingEveryAllowedOne() {
        assertThatThrownBy(() -> PoolScope.fromParam("private"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope")
                .hasMessageContaining("'repo', 'portable', 'external'");
    }

    @Test
    void jsonValueIsLowercase() {
        assertThat(PoolScope.REPO.jsonValue()).isEqualTo("repo");
        assertThat(PoolScope.PORTABLE.jsonValue()).isEqualTo("portable");
        assertThat(PoolScope.EXTERNAL.jsonValue()).isEqualTo("external");
    }
}
