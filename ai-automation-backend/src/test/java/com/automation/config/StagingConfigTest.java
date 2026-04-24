package com.automation.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class StagingConfigTest {

    private StagingConfig config;

    @BeforeEach
    void setUp() {
        config = new StagingConfig();
    }

    // --- Annotation tests ---

    @Test
    void should_haveConfigurationAnnotation_when_classIsInspected() {
        Configuration annotation = StagingConfig.class.getAnnotation(Configuration.class);
        assertThat(annotation).isNotNull();
    }

    @Test
    void should_haveConfigurationPropertiesAnnotation_when_classIsInspected() {
        ConfigurationProperties annotation = StagingConfig.class.getAnnotation(ConfigurationProperties.class);
        assertThat(annotation).isNotNull();
    }

    @Test
    void should_haveCorrectPrefix_when_configurationPropertiesAnnotationInspected() {
        ConfigurationProperties annotation = StagingConfig.class.getAnnotation(ConfigurationProperties.class);
        assertThat(annotation.prefix()).isEqualTo("automation.staging");
    }

    // --- Default value tests (Stable instance) ---

    @Test
    void should_returnDefaultComposeFile_when_notExplicitlySet() {
        assertThat(config.getComposeFile()).isEqualTo("docker-compose.stable.yml");
    }

    @Test
    void should_returnEmptyBackendDir_when_notExplicitlySet() {
        assertThat(config.getBackendDir()).isEmpty();
    }

    @Test
    void should_returnEmptyFrontendDir_when_notExplicitlySet() {
        assertThat(config.getFrontendDir()).isEmpty();
    }

    @Test
    void should_returnDefaultContainerName_when_notExplicitlySet() {
        assertThat(config.getContainerName()).isEqualTo("mysuricates-stable");
    }

    @Test
    void should_returnDefaultStableFrontendDir_when_notExplicitlySet() {
        assertThat(config.getStableFrontendDir()).isEqualTo("stable-frontend");
    }

    @Test
    void should_returnDefaultHealthUrl_when_notExplicitlySet() {
        assertThat(config.getHealthUrl()).isEqualTo("http://localhost:9091/api/v1/system/health");
    }

    @Test
    void should_returnTrueForAutoStart_when_notExplicitlySet() {
        assertThat(config.isAutoStart()).isTrue();
    }

    // --- Default value tests (Test instance) ---

    @Test
    void should_returnDefaultTestComposeFile_when_notExplicitlySet() {
        assertThat(config.getTestComposeFile()).isEqualTo("docker-compose.test.yml");
    }

    @Test
    void should_returnDefaultTestContainerName_when_notExplicitlySet() {
        assertThat(config.getTestContainerName()).isEqualTo("mysuricates-test");
    }

    @Test
    void should_returnDefaultTestHealthUrl_when_notExplicitlySet() {
        assertThat(config.getTestHealthUrl()).isEqualTo("http://localhost:8090/api/v1/system/health");
    }

    @Test
    void should_returnTrueForTestAutoStart_when_notExplicitlySet() {
        assertThat(config.isTestAutoStart()).isTrue();
    }

    // --- Setter tests (Stable instance) ---

    @Test
    void should_updateComposeFile_when_setterCalled() {
        config.setComposeFile("custom-compose.yml");
        assertThat(config.getComposeFile()).isEqualTo("custom-compose.yml");
    }

    @Test
    void should_updateBackendDir_when_setterCalled() {
        config.setBackendDir("/opt/backend");
        assertThat(config.getBackendDir()).isEqualTo("/opt/backend");
    }

    @Test
    void should_updateFrontendDir_when_setterCalled() {
        config.setFrontendDir("/opt/frontend");
        assertThat(config.getFrontendDir()).isEqualTo("/opt/frontend");
    }

    @Test
    void should_updateContainerName_when_setterCalled() {
        config.setContainerName("custom-container");
        assertThat(config.getContainerName()).isEqualTo("custom-container");
    }

    @Test
    void should_updateStableFrontendDir_when_setterCalled() {
        config.setStableFrontendDir("custom-stable-frontend");
        assertThat(config.getStableFrontendDir()).isEqualTo("custom-stable-frontend");
    }

    @Test
    void should_updateHealthUrl_when_setterCalled() {
        config.setHealthUrl("http://example.com/health");
        assertThat(config.getHealthUrl()).isEqualTo("http://example.com/health");
    }

    @Test
    void should_updateAutoStart_when_setterCalled() {
        config.setAutoStart(false);
        assertThat(config.isAutoStart()).isFalse();
    }

    // --- Setter tests (Test instance) ---

    @Test
    void should_updateTestComposeFile_when_setterCalled() {
        config.setTestComposeFile("docker-compose.custom-test.yml");
        assertThat(config.getTestComposeFile()).isEqualTo("docker-compose.custom-test.yml");
    }

    @Test
    void should_updateTestContainerName_when_setterCalled() {
        config.setTestContainerName("custom-test-container");
        assertThat(config.getTestContainerName()).isEqualTo("custom-test-container");
    }

    @Test
    void should_updateTestHealthUrl_when_setterCalled() {
        config.setTestHealthUrl("http://example.com:8090/health");
        assertThat(config.getTestHealthUrl()).isEqualTo("http://example.com:8090/health");
    }

    @Test
    void should_updateTestAutoStart_when_setterCalled() {
        config.setTestAutoStart(false);
        assertThat(config.isTestAutoStart()).isFalse();
    }

    // --- Edge cases ---

    @Test
    void should_acceptNullComposeFile_when_setterCalledWithNull() {
        config.setComposeFile(null);
        assertThat(config.getComposeFile()).isNull();
    }

    @Test
    void should_acceptEmptyHealthUrl_when_setterCalledWithEmptyString() {
        config.setHealthUrl("");
        assertThat(config.getHealthUrl()).isEmpty();
    }
}
