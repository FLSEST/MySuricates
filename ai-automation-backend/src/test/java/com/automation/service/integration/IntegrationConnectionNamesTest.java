package com.automation.service.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationConnectionNamesTest {

    @Test
    void missingOrBlankName_isDefault() {
        assertThat(IntegrationConnectionNames.normalize(null)).isEqualTo("default");
        assertThat(IntegrationConnectionNames.normalize("")).isEqualTo("default");
        assertThat(IntegrationConnectionNames.normalize("  ")).isEqualTo("default");
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "work", "a", "team-2", "9", "abcdefghijabcdefghijabcdefghijabcdefghij"})
    void validNames_passTrimmed(String name) {
        assertThat(IntegrationConnectionNames.normalize(" " + name + " ")).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Work", "-work", "work space", "work/x", "../x", "work_1", "ä", "wo\nrk",
            "abcdefghijabcdefghijabcdefghijabcdefghijx"})
    void invalidNames_areRejected(String name) {
        assertThatThrownBy(() -> IntegrationConnectionNames.normalize(name))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid connection name");
    }

    @Test
    void label_omitsTheDefaultName() {
        assertThat(IntegrationConnectionNames.label("github", "default")).isEqualTo("github");
        assertThat(IntegrationConnectionNames.label("github", null)).isEqualTo("github");
        assertThat(IntegrationConnectionNames.label("github", "work")).isEqualTo("github/work");
    }

    @Test
    void order_putsDefaultFirst() {
        assertThat(Stream.of("zeta", "default", "alpha").sorted(IntegrationConnectionNames.ORDER).toList())
                .isEqualTo(List.of("default", "alpha", "zeta"));
    }
}
