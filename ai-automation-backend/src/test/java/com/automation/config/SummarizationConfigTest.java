package com.automation.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class SummarizationConfigTest {

    private SummarizationConfig config;

    @BeforeEach
    void setUp() {
        config = new SummarizationConfig();
    }

    @Nested
    @DisplayName("Class annotations")
    class ClassAnnotations {

        @Test
        void should_be_annotated_with_Configuration() {
            assertThat(SummarizationConfig.class.isAnnotationPresent(Configuration.class)).isTrue();
        }

        @Test
        void should_be_annotated_with_ConfigurationProperties_using_correct_prefix() {
            ConfigurationProperties props = SummarizationConfig.class.getAnnotation(ConfigurationProperties.class);
            assertThat(props).isNotNull();
            assertThat(props.prefix()).isEqualTo("automation.summarization");
        }
    }

    @Nested
    @DisplayName("Default values")
    class Defaults {

        @Test
        void should_default_baseDir_to_user_home_autotrigger_summarization() {
            String expected = System.getProperty("user.home") + "/.autotrigger/summarization";
            assertThat(config.getBaseDir()).isEqualTo(expected);
        }

        @Test
        void should_default_timeoutSeconds_to_120() {
            assertThat(config.getTimeoutSeconds()).isEqualTo(120);
        }
    }

    @Nested
    @DisplayName("Setters and getters")
    class Accessors {

        @Test
        void should_update_baseDir_via_setter() {
            config.setBaseDir("/tmp/custom-summarization");
            assertThat(config.getBaseDir()).isEqualTo("/tmp/custom-summarization");
        }

        @Test
        void should_update_timeoutSeconds_via_setter() {
            config.setTimeoutSeconds(300);
            assertThat(config.getTimeoutSeconds()).isEqualTo(300);
        }

        @Test
        void should_allow_baseDir_to_be_set_to_null() {
            config.setBaseDir(null);
            assertThat(config.getBaseDir()).isNull();
        }

        @Test
        void should_allow_zero_timeoutSeconds() {
            config.setTimeoutSeconds(0);
            assertThat(config.getTimeoutSeconds()).isZero();
        }
    }

    @Test
    void should_be_instantiable_as_a_plain_bean() {
        assertThat(new SummarizationConfig()).isNotNull();
    }
}
