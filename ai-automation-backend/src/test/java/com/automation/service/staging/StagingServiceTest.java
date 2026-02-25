package com.automation.service.staging;

import com.automation.config.StagingConfig;
import com.automation.dto.response.StagingStatusResponse;
import com.automation.dto.response.SyncResult;
import com.automation.entity.*;
import com.automation.repository.ProjectRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StagingServiceTest {

    private StagingConfig config;
    private ProjectRepository projectRepository;
    private WorkflowRepository workflowRepository;
    private SkillRepository skillRepository;
    private ObjectMapper objectMapper;
    private StagingService service;

    @BeforeEach
    void setUp() {
        config = new StagingConfig();
        config.setContainerName("mysuricates-stable");
        config.setHealthUrl("http://localhost:9091/api/v1/system/health");
        config.setComposeFile("docker-compose.stable.yml");
        config.setStableFrontendDir("stable-frontend");
        projectRepository = mock(ProjectRepository.class);
        workflowRepository = mock(WorkflowRepository.class);
        skillRepository = mock(SkillRepository.class);
        objectMapper = new ObjectMapper();
        service = new StagingService(config, projectRepository, workflowRepository, skillRepository, objectMapper);
    }

    @Test
    void getStatus_returnsIdleByDefault() {
        StagingStatusResponse status = service.getStatus();

        assertThat(status.promotePhase()).isEqualTo("IDLE");
        assertThat(status.promoteStep()).isZero();
        assertThat(status.promoteTotalSteps()).isEqualTo(5);
        assertThat(status.error()).isNull();
    }

    @Test
    void getStatus_totalStepsIsAlwaysFive() {
        StagingStatusResponse status = service.getStatus();
        assertThat(status.promoteTotalSteps()).isEqualTo(5);
    }

    @Test
    void cancelPromote_doesNotThrowWhenIdle() {
        assertThatCode(() -> service.cancelPromote()).doesNotThrowAnyException();
    }

    @Test
    void start_throwsWithoutStableFrontend() {
        config.setBackendDir("/tmp/nonexistent-dir");

        assertThatThrownBy(() -> service.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No stable-frontend/ found");
    }

    @Test
    void resolveBackendDir_usesConfigIfSet() {
        config.setBackendDir("/custom/path");
        assertThat(service.resolveBackendDir().toString()).isEqualTo("/custom/path");
    }

    @Test
    void resolveBackendDir_autoDetectsFromCwd() {
        config.setBackendDir("");
        // Should not throw — returns cwd or child
        assertThatCode(() -> service.resolveBackendDir()).doesNotThrowAnyException();
    }

    @Test
    void resolveFrontendDir_usesConfigIfSet() {
        config.setFrontendDir("/custom/frontend");
        assertThat(service.resolveFrontendDir().toString()).isEqualTo("/custom/frontend");
    }

    @Test
    void resolveFrontendDir_derivesFromBackendDir() {
        config.setFrontendDir("");
        config.setBackendDir("/home/user/project/ai-automation-backend");
        assertThat(service.resolveFrontendDir().toString())
                .isEqualTo("/home/user/project/ai-automation-frontend");
    }

    @Test
    void findLastSnapshot_returnsNullWhenNoTags() {
        // Running in test environment where git tags likely don't have stable-* tags
        assertThatCode(() -> service.findLastSnapshot()).doesNotThrowAnyException();
    }

    @Test
    void isContainerRunning_returnsFalseWhenDockerNotAvailable() {
        config.setContainerName("nonexistent-container-12345");
        assertThat(service.isContainerRunning()).isFalse();
    }

    @Test
    void isHealthy_returnsFalseWhenServiceNotRunning() {
        config.setHealthUrl("http://localhost:59999/nonexistent");
        assertThat(service.isHealthy()).isFalse();
    }

    @Test
    void syncDefinitions_throwsWhenStableNotHealthy() {
        // Default config points to localhost:9091 which is not running in tests
        config.setHealthUrl("http://localhost:59999/nonexistent");

        assertThatThrownBy(() -> service.syncDefinitions())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running or not healthy");
    }

    @Test
    void deriveStableBaseUrl_extractsFromHealthUrl() {
        config.setHealthUrl("http://localhost:9091/api/v1/system/health");
        assertThat(service.deriveStableBaseUrl()).isEqualTo("http://localhost:9091/api/v1");
    }

    @Test
    void deriveStableBaseUrl_fallsBackToDefault() {
        config.setHealthUrl("http://localhost:9091/health");
        assertThat(service.deriveStableBaseUrl()).isEqualTo("http://localhost:9091/api/v1");
    }
}
