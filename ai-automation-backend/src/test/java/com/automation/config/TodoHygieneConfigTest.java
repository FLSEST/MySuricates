package com.automation.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class TodoHygieneConfigTest {

    private TodoHygieneConfig config;

    @BeforeEach
    void setUp() {
        config = new TodoHygieneConfig();
    }

    @Test
    void should_be_instantiable_as_a_plain_bean() {
        assertThat(new TodoHygieneConfig()).isNotNull();
    }

    @Nested
    @DisplayName("Class annotations")
    class ClassAnnotations {

        @Test
        void should_be_annotated_with_Configuration() {
            assertThat(TodoHygieneConfig.class.isAnnotationPresent(Configuration.class)).isTrue();
        }

        @Test
        void should_be_annotated_with_ConfigurationProperties_using_correct_prefix() {
            ConfigurationProperties props = TodoHygieneConfig.class.getAnnotation(ConfigurationProperties.class);
            assertThat(props).isNotNull();
            assertThat(props.prefix()).isEqualTo("automation.todo.hygiene");
        }
    }

    @Nested
    @DisplayName("Default values")
    class Defaults {

        @Test
        void should_default_enabled_to_false_so_scheduled_job_stays_dormant() {
            assertThat(config.isEnabled()).isFalse();
        }

        @Test
        void should_default_staleDays_to_30() {
            assertThat(config.getStaleDays()).isEqualTo(30);
        }

        @Test
        void should_default_maxPerRun_to_200() {
            assertThat(config.getMaxPerRun()).isEqualTo(200);
        }

        @Test
        void should_have_positive_defaults_for_staleDays_and_maxPerRun() {
            assertThat(config.getStaleDays()).isPositive();
            assertThat(config.getMaxPerRun()).isPositive();
        }
    }

    @Nested
    @DisplayName("Setters and getters")
    class Accessors {

        @Test
        void should_update_enabled_via_setter() {
            config.setEnabled(true);
            assertThat(config.isEnabled()).isTrue();
        }

        @Test
        void should_update_staleDays_via_setter() {
            config.setStaleDays(7);
            assertThat(config.getStaleDays()).isEqualTo(7);
        }

        @Test
        void should_update_maxPerRun_via_setter() {
            config.setMaxPerRun(50);
            assertThat(config.getMaxPerRun()).isEqualTo(50);
        }

        @Test
        void should_allow_zero_staleDays() {
            config.setStaleDays(0);
            assertThat(config.getStaleDays()).isZero();
        }

        @Test
        void should_allow_zero_maxPerRun() {
            config.setMaxPerRun(0);
            assertThat(config.getMaxPerRun()).isZero();
        }

        @Test
        void should_allow_disabling_after_enabling() {
            config.setEnabled(true);
            config.setEnabled(false);
            assertThat(config.isEnabled()).isFalse();
        }
    }
}
