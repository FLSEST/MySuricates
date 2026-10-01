package com.automation.service.system;

import com.automation.dto.response.RemoteAccessResponse;
import com.automation.exception.TailscaleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TailscaleServiceTest {

    @Test
    void enable_whenDisabledInConfig_throwsIllegalState() {
        TailscaleService service = new TailscaleService(false);

        assertThatThrownBy(service::enable)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled in configuration");
    }

    @Test
    void disable_whenDisabledInConfig_throwsIllegalState() {
        TailscaleService service = new TailscaleService(false);

        assertThatThrownBy(service::disable)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled in configuration");
    }

    @Test
    void getStatus_whenDisabledInConfig_returnsDisabled() {
        TailscaleService service = new TailscaleService(false);

        RemoteAccessResponse response = service.getStatus();

        assertThat(response.enabled()).isFalse();
        assertThat(response.ip()).isNull();
        assertThat(response.hostname()).isNull();
    }

    @Test
    void isConfigEnabled_whenFalse_returnsFalse() {
        TailscaleService service = new TailscaleService(false);

        assertThat(service.isConfigEnabled()).isFalse();
    }

    @Test
    void isConfigEnabled_whenTrue_returnsTrue() {
        TailscaleService service = new TailscaleService(true);

        assertThat(service.isConfigEnabled()).isTrue();
    }

    @Test
    void followsRuntimeSwitchesOfTheRemoteAccessModule() {
        var registry = com.automation.modules.ModuleRegistry.of(java.util.Map.of("ui-editable", "true"), java.util.Map.of());
        TailscaleService service = new TailscaleService(registry);
        assertThat(service.isConfigEnabled()).isFalse();

        registry.applyUiOverrides(java.util.Map.of(com.automation.modules.ModuleCatalog.REMOTE_ACCESS, true));

        assertThat(service.isConfigEnabled()).isTrue();
    }

    @Test
    void enable_whenEnabledButTailscaleNotInstalled_throwsTailscaleException() {
        assumeTrue(!isTailscaleInstalled(),
                "Skipped: tailscale is installed on this system, so the command won't fail");

        TailscaleService service = new TailscaleService(true);

        // On a system without tailscale installed, this should throw
        assertThatThrownBy(service::enable)
                .isInstanceOf(TailscaleException.class);
    }

    private static boolean isTailscaleInstalled() {
        try {
            Process p = new ProcessBuilder("which", "tailscale")
                    .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    void getStatus_whenConfigEnabled_delegatesToTailscale() {
        TailscaleService service = new TailscaleService(true);

        // Should not throw — returns a valid response regardless of Tailscale state
        RemoteAccessResponse response = service.getStatus();
        assertThat(response).isNotNull();
    }

    // --- Container instances: no supported Tailscale path there ---

    @Test
    void enable_whenInContainer_throwsClearErrorInsteadOfRunningCli() {
        TailscaleService service = new TailscaleService(() -> true, env("container"), "");

        assertThatThrownBy(service::enable)
                .isInstanceOf(TailscaleException.class)
                .hasMessageContaining("container")
                .hasMessageContaining("tailscale serve");
    }

    @Test
    void disable_whenInContainer_throwsClearErrorInsteadOfRunningCli() {
        TailscaleService service = new TailscaleService(() -> true, env("container"), "");

        assertThatThrownBy(service::disable)
                .isInstanceOf(TailscaleException.class)
                .hasMessageContaining("container");
    }

    @Test
    void getStatus_whenInContainerAndModuleEnabled_reportsInContainerWithoutCallingCli() {
        TailscaleService service = new TailscaleService(() -> true, env("container"), "");

        RemoteAccessResponse response = service.getStatus();

        assertThat(response.enabled()).isFalse();
        assertThat(response.inContainer()).isTrue();
    }

    @Test
    void getStatus_whenOnHost_reportsNotInContainer() {
        TailscaleService service = new TailscaleService(true);

        RemoteAccessResponse response = service.getStatus();

        assertThat(response.inContainer()).isFalse();
    }

    @Test
    void inContainer_whenAutomationInstanceContainerSet_detectsContainerLikeAiProviderStatusService() {
        TailscaleService service = new TailscaleService(() -> true, env(null), "mysuricates-dev-container");

        RemoteAccessResponse response = service.getStatus();

        assertThat(response.inContainer()).isTrue();
    }

    private static java.util.function.UnaryOperator<String> env(String runtime) {
        return key -> "MYSURICATES_RUNTIME".equals(key) ? runtime : null;
    }
}
