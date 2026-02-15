package com.automation.service.system;

import com.automation.dto.response.RemoteAccessResponse;
import com.automation.exception.TailscaleException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

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
    void enable_whenEnabledButTailscaleNotInstalled_throwsTailscaleException() {
        TailscaleService service = new TailscaleService(true);

        // On a system without tailscale installed, this should throw
        assertThatThrownBy(service::enable)
                .isInstanceOf(TailscaleException.class);
    }

    @Test
    void getStatus_whenEnabledButTailscaleNotRunning_returnsDisabled() {
        TailscaleService service = new TailscaleService(true);

        RemoteAccessResponse response = service.getStatus();

        // Tailscale not running on test system -> returns disabled
        assertThat(response.enabled()).isFalse();
    }
}
