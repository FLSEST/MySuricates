package com.automation.service.staging;

import com.automation.config.StagingConfig;
import com.automation.dto.response.StagingStatusResponse;
import com.automation.dto.response.SyncResult;
import com.automation.entity.*;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StagingServiceTest {

    private StagingConfig config;
    private InstanceSyncHelper syncHelper;
    private ApiKeyService apiKeyService;
    private DockerContainerManager dockerManager;
    private StagingComposeCommand compose;
    private StagingService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        config = new StagingConfig();
        config.setContainerName("mysuricates-stable");
        config.setHealthUrl("http://localhost:9091/api/v1/system/health");
        config.setComposeFile("docker-compose.stable.yml");
        config.setStableFrontendDir("stable-frontend");
        syncHelper = mock(InstanceSyncHelper.class);
        apiKeyService = mock(ApiKeyService.class);
        dockerManager = mock(DockerContainerManager.class);
        compose = mock(StagingComposeCommand.class);
        service = new StagingService(config, syncHelper, mock(InstanceHttpClient.class), mock(SyncPreviewService.class), apiKeyService, dockerManager, compose);
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
    void syncDefinitions_missingKeyFile_failsClearlyInsteadOfReadingTheDatabase() {
        when(dockerManager.isHealthy(anyString())).thenReturn(true);
        when(dockerManager.readApiKey(eq("mysuricates-stable"), any())).thenThrow(new com.automation.exception.LocalizedStateException(
                com.automation.exception.ErrorMessage.CONTAINER_API_KEY_MISSING,
                java.util.Map.of("container", "mysuricates-stable", "file", "/home/user/.mysuricates/api-key")));

        assertThatThrownBy(() -> service.syncDefinitions())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/home/user/.mysuricates/api-key");
        verify(syncHelper, never()).syncDefinitionsTo(any(), any(), any());
    }

    @Test
    void syncDefinitions_usesTheKeyFromTheContainersKeyFile() {
        when(dockerManager.isHealthy(anyString())).thenReturn(true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("dev-key");
        when(dockerManager.readApiKey("mysuricates-stable", "dev-key")).thenReturn("stable-key");

        service.syncDefinitions();

        verify(syncHelper).syncDefinitionsTo("http://localhost:9091/api/v1", "stable-key", null);
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
    void promote_canBeCalledFromIdleState() throws InterruptedException {
        // This tests the IDLE singleton fix — promote() should not throw when state is IDLE.
        // It fails internally (no Docker, missing backend dir), but must not throw "already in progress".
        pointAtMissingProjectDirs();

        assertThatCode(() -> service.promote()).doesNotThrowAnyException();

        // Ends in ERROR (step 2 cannot run in the missing backend dir), not stuck in a running phase
        StagingStatusResponse status = awaitPromoteSettled();
        assertThat(status.promotePhase()).isEqualTo("ERROR");
        assertThat(status.error()).contains("mvn");
    }

    @Test
    void promote_rejectsDoubleInvocation() throws InterruptedException {
        pointAtMissingProjectDirs();
        // Hold the first promote inside step 1 ("compose down"): its phase stays STOPPING until
        // released, so the second call no longer races the async pipeline (TODO 0f68834a).
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(compose.command(any(), any(), eq("stable"), eq("down"))).thenAnswer(inv -> {
            holding.countDown();
            release.await(30, TimeUnit.SECONDS);
            return null;
        });

        service.promote();
        try {
            assertThat(holding.await(30, TimeUnit.SECONDS)).as("first promote reached step 1").isTrue();

            assertThatThrownBy(() -> service.promote())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already in progress: STOPPING");
        } finally {
            release.countDown();
        }
        // Released, the first promote runs on and fails in the missing backend dir
        assertThat(awaitPromoteSettled().promotePhase()).isEqualTo("ERROR");
    }

    /** Missing (not just unlikely) dirs: the pipeline fails at step 2 instead of building anything. */
    private void pointAtMissingProjectDirs() {
        config.setBackendDir(tempDir.resolve("missing-backend").toString());
        config.setFrontendDir(tempDir.resolve("missing-frontend").toString());
    }

    /** Waits until the async pipeline has ended; the bound only guards against a hang, it is no timing bet. */
    private StagingStatusResponse awaitPromoteSettled() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        StagingStatusResponse status = service.getStatus();
        while (!Set.of("IDLE", "ERROR").contains(status.promotePhase()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
            status = service.getStatus();
        }
        return status;
    }
}
