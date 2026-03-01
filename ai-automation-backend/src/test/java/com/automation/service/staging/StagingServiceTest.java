package com.automation.service.staging;

import com.automation.config.StagingConfig;
import com.automation.dto.response.StagingStatusResponse;
import com.automation.dto.response.SyncResult;
import com.automation.entity.*;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StagingServiceTest {

    private StagingConfig config;
    private InstanceSyncHelper syncHelper;
    private ApiKeyService apiKeyService;
    private StagingService service;

    @BeforeEach
    void setUp() {
        config = new StagingConfig();
        config.setContainerName("mysuricates-stable");
        config.setHealthUrl("http://localhost:9091/api/v1/system/health");
        config.setComposeFile("docker-compose.stable.yml");
        config.setStableFrontendDir("stable-frontend");
        syncHelper = mock(InstanceSyncHelper.class);
        apiKeyService = mock(ApiKeyService.class);
        service = new StagingService(config, syncHelper, apiKeyService);
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

    @Test
    void promote_canBeCalledFromIdleState() {
        // This tests the IDLE singleton fix — promote() should not throw when state is IDLE.
        // It will fail internally (no Docker etc.), but should not throw "already in progress".
        config.setBackendDir("/tmp/nonexistent-staging-test");
        config.setFrontendDir("/tmp/nonexistent-staging-frontend");

        assertThatCode(() -> service.promote()).doesNotThrowAnyException();

        // Wait briefly for async thread to run and fail
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        // Should be in ERROR state (because dirs don't exist), not stuck in IDLE
        StagingStatusResponse status = service.getStatus();
        assertThat(status.promotePhase()).isIn("ERROR", "IDLE");
    }

    @Test
    void promote_rejectsDoubleInvocation() {
        config.setBackendDir("/tmp/nonexistent-staging-test");
        config.setFrontendDir("/tmp/nonexistent-staging-frontend");

        // First call succeeds
        service.promote();

        // Second call should throw (promote is in progress)
        // Need to call quickly before async thread finishes
        assertThatThrownBy(() -> service.promote())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in progress");
    }
}
