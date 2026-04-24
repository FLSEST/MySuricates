package com.automation.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowPoolConfigTest {

    private WorkflowPoolConfig config;

    @BeforeEach
    void setUp() {
        config = new WorkflowPoolConfig();
    }

    @Test
    void should_haveDefaultDirectory_when_newlyInstantiated() {
        assertThat(config.getDirectory()).isEqualTo("workflow-pool");
    }

    @Test
    void should_beEnabledByDefault_when_newlyInstantiated() {
        assertThat(config.isEnabled()).isTrue();
    }

    @Test
    void should_updateDirectory_when_setterCalled() {
        config.setDirectory("custom-pool");
        assertThat(config.getDirectory()).isEqualTo("custom-pool");
    }

    @Test
    void should_acceptEmptyDirectory_when_setterCalledWithEmptyString() {
        config.setDirectory("");
        assertThat(config.getDirectory()).isEmpty();
    }

    @Test
    void should_acceptNullDirectory_when_setterCalledWithNull() {
        config.setDirectory(null);
        assertThat(config.getDirectory()).isNull();
    }

    @Test
    void should_disablePool_when_setEnabledFalse() {
        config.setEnabled(false);
        assertThat(config.isEnabled()).isFalse();
    }

    @Test
    void should_reEnablePool_when_setEnabledTrueAfterDisable() {
        config.setEnabled(false);
        config.setEnabled(true);
        assertThat(config.isEnabled()).isTrue();
    }

    @Test
    void should_beAnnotatedAsConfiguration() {
        Configuration annotation = WorkflowPoolConfig.class.getAnnotation(Configuration.class);
        assertThat(annotation).isNotNull();
    }

    @Test
    void should_beAnnotatedAsConfigurationPropertiesWithCorrectPrefix() {
        ConfigurationProperties annotation = WorkflowPoolConfig.class.getAnnotation(ConfigurationProperties.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.prefix()).isEqualTo("automation.pool");
    }

    @Test
    void should_supportIndependentInstances_when_multipleInstancesCreated() {
        WorkflowPoolConfig other = new WorkflowPoolConfig();
        config.setDirectory("a");
        other.setDirectory("b");
        config.setEnabled(false);
        other.setEnabled(true);

        assertThat(config.getDirectory()).isEqualTo("a");
        assertThat(other.getDirectory()).isEqualTo("b");
        assertThat(config.isEnabled()).isFalse();
        assertThat(other.isEnabled()).isTrue();
    }
}
