package com.automation.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class TaskCommandConfigTest {

    private TaskCommandConfig config;

    @BeforeEach
    void setUp() {
        config = new TaskCommandConfig();
    }

    @Test
    void should_be_instantiable_as_a_plain_bean() {
        assertThat(new TaskCommandConfig()).isNotNull();
    }

    @Nested
    @DisplayName("Class annotations")
    class ClassAnnotations {

        @Test
        void should_be_annotated_with_Configuration() {
            assertThat(TaskCommandConfig.class.isAnnotationPresent(Configuration.class))
                    .as("TaskCommandConfig must be annotated with @Configuration")
                    .isTrue();
        }

        @Test
        void should_be_annotated_with_ConfigurationProperties_using_correct_prefix() {
            ConfigurationProperties props = TaskCommandConfig.class.getAnnotation(ConfigurationProperties.class);
            assertThat(props).isNotNull();
            assertThat(props.prefix()).isEqualTo("automation.task-command");
        }
    }

    @Nested
    @DisplayName("Default values")
    class Defaults {

        @Test
        void should_default_timeoutSeconds_to_60() {
            assertThat(config.getTimeoutSeconds()).isEqualTo(60);
        }

        @Test
        void should_have_positive_default_timeout_leaving_headroom_for_task_cli_calls() {
            assertThat(config.getTimeoutSeconds()).isPositive();
        }
    }

    @Nested
    @DisplayName("Setters and getters (simulating @ConfigurationProperties injection)")
    class Accessors {

        @Test
        void should_update_timeoutSeconds_via_setter_when_property_is_injected() {
            config.setTimeoutSeconds(120);
            assertThat(config.getTimeoutSeconds()).isEqualTo(120);
        }

        @Test
        void should_allow_zero_timeoutSeconds() {
            config.setTimeoutSeconds(0);
            assertThat(config.getTimeoutSeconds()).isZero();
        }

        @Test
        void should_allow_overriding_timeout_multiple_times() {
            config.setTimeoutSeconds(30);
            config.setTimeoutSeconds(90);
            assertThat(config.getTimeoutSeconds()).isEqualTo(90);
        }
    }
}
