package com.automation.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeedVersionsTest {

    @ParameterizedTest(name = "{0} > {1} = {2}")
    @CsvSource({
            "1.0.1, 1.0.0, true",
            "1.1.0, 1.0.9, true",
            "2.0.0, 1.99.99, true",
            "1.10.0, 1.9.0, true",   // numeric, not lexicographic
            "1.9.0, 1.10.0, false",
            "1.0.0, 1.0.0, false",
            "1.0.0, 1.0.1, false",
            "1.0.1, 1.0, true",      // missing parts count as 0
            "1.0, 1.0.0, false",
            "1.0.0, 1, false",
            "2, 1.9.9, true"
    })
    void should_compare_numerically_part_by_part(String newVersion, String oldVersion, boolean expected) {
        assertThat(SeedVersions.isNewer(newVersion, oldVersion)).isEqualTo(expected);
    }

    @Test
    void should_treat_missing_old_version_as_older() {
        // rows seeded before versioning existed must be updated
        assertThat(SeedVersions.isNewer("1.0.0", null)).isTrue();
        assertThat(SeedVersions.isNewer("1.0.0", "  ")).isTrue();
    }

    @Test
    void should_never_treat_a_missing_new_version_as_newer() {
        assertThat(SeedVersions.isNewer(null, "1.0.0")).isFalse();
        assertThat(SeedVersions.isNewer("", "1.0.0")).isFalse();
    }

    @Test
    void should_prefer_update_when_both_versions_are_missing() {
        assertThat(SeedVersions.isNewer(null, null)).isTrue();
    }

    @Test
    void should_reject_non_numeric_parts() {
        // pre-release suffixes are not supported; the version gate tests keep shipped files numeric
        assertThatThrownBy(() -> SeedVersions.isNewer("1.0.0-beta", "1.0.0"))
                .isInstanceOf(NumberFormatException.class);
    }
}
