package com.automation.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class FactoryConfigTest {

    private FactoryConfig config;

    @BeforeEach
    void setUp() {
        config = new FactoryConfig();
    }

    // --- Annotation tests ---

    @Test
    void should_haveConfigurationAnnotation_when_classIsInspected() {
        assertThat(FactoryConfig.class.isAnnotationPresent(Configuration.class))
                .as("FactoryConfig must be annotated with @Configuration")
                .isTrue();
    }

    @Test
    void should_haveConfigurationPropertiesAnnotation_when_classIsInspected() {
        assertThat(FactoryConfig.class.isAnnotationPresent(ConfigurationProperties.class))
                .as("FactoryConfig must be annotated with @ConfigurationProperties")
                .isTrue();
    }

    @Test
    void should_haveCorrectPrefix_when_configurationPropertiesIsInspected() {
        ConfigurationProperties annotation = FactoryConfig.class.getAnnotation(ConfigurationProperties.class);
        assertThat(annotation.prefix()).isEqualTo("automation.factory");
    }

    // --- Default value tests ---

    @Test
    void should_returnClaude_when_providerDefaultIsQueried() {
        assertThat(config.getProvider()).isEqualTo("CLAUDE");
    }

    @Test
    void should_returnUserHomeBasedPath_when_baseDirDefaultIsQueried() {
        String expectedDir = DataHome.dir().resolve("factory").toString();
        assertThat(config.getBaseDir()).isEqualTo(expectedDir);
    }

    @Test
    void should_endWithFactory_when_baseDirDefaultIsQueried() {
        assertThat(config.getBaseDir()).endsWith("/factory");
    }

    @Test
    void should_return600_when_timeoutSecondsDefaultIsQueried() {
        assertThat(config.getTimeoutSeconds()).isEqualTo(600);
    }

    // --- Setter/Override tests (simulating @ConfigurationProperties injection) ---

    @Test
    void should_returnCodex_when_providerIsSetToCodex() {
        config.setProvider("CODEX");
        assertThat(config.getProvider()).isEqualTo("CODEX");
    }

    @Test
    void should_returnCustomBaseDir_when_baseDirIsOverridden() {
        config.setBaseDir("/custom/factory/path");
        assertThat(config.getBaseDir()).isEqualTo("/custom/factory/path");
    }

    @Test
    void should_returnCustomTimeout_when_timeoutSecondsIsOverridden() {
        config.setTimeoutSeconds(120);
        assertThat(config.getTimeoutSeconds()).isEqualTo(120);
    }

    // --- Edge cases ---

    @Test
    void should_allowNullProvider_when_setToNull() {
        config.setProvider(null);
        assertThat(config.getProvider()).isNull();
    }

    @Test
    void should_allowNullBaseDir_when_setToNull() {
        config.setBaseDir(null);
        assertThat(config.getBaseDir()).isNull();
    }

    @Test
    void should_allowZeroTimeout_when_setToZero() {
        config.setTimeoutSeconds(0);
        assertThat(config.getTimeoutSeconds()).isZero();
    }

    @Test
    void should_allowNegativeTimeout_when_setToNegative() {
        config.setTimeoutSeconds(-1);
        assertThat(config.getTimeoutSeconds()).isEqualTo(-1);
    }

    @Test
    void should_allowEmptyProvider_when_setToEmpty() {
        config.setProvider("");
        assertThat(config.getProvider()).isEmpty();
    }
}
