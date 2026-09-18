package com.automation.service.pool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code scope} ist das Pool-Metadatum, das repo-gebundene von portablen Eintraegen trennt
 * (docs/workflow-pool-kuratierung.md).
 */
class PoolScopeTest {

    @Test
    void defaultsToPortableWhenUnset() {
        assertThat(PoolScope.fromParam(null)).isEqualTo(PoolScope.PORTABLE);
        assertThat(PoolScope.fromParam("  ")).isEqualTo(PoolScope.PORTABLE);
    }

    @Test
    void parsesBothValuesCaseInsensitively() {
        assertThat(PoolScope.fromParam("repo")).isEqualTo(PoolScope.REPO);
        assertThat(PoolScope.fromParam("REPO")).isEqualTo(PoolScope.REPO);
        assertThat(PoolScope.fromParam("Portable")).isEqualTo(PoolScope.PORTABLE);
    }

    @Test
    void rejectsUnknownValue() {
        assertThatThrownBy(() -> PoolScope.fromParam("private"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
    }

    @Test
    void jsonValueIsLowercase() {
        assertThat(PoolScope.REPO.jsonValue()).isEqualTo("repo");
        assertThat(PoolScope.PORTABLE.jsonValue()).isEqualTo("portable");
    }
}
