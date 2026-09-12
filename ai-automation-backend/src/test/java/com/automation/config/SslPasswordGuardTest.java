package com.automation.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSL enabled + the documented dev default keystore password (docs/GETTING-STARTED.md §7,
 * scripts/generate-dev-cert.sh) must refuse to boot rather than silently serve HTTPS with a
 * password anyone can read in the repo.
 */
class SslPasswordGuardTest {

    @Test
    void should_throw_when_sslEnabledAndPasswordIsDevDefault() {
        SslPasswordGuard guard = new SslPasswordGuard(true, "mysuricates");

        assertThatThrownBy(guard::checkPassword)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AUTOMATION_SSL_ENABLED=true requires a non-default AUTOMATION_SSL_PASSWORD "
                        + "(dev default 'mysuricates' refused)");
    }

    @Test
    void should_notThrow_when_sslEnabledAndPasswordIsCustom() {
        SslPasswordGuard guard = new SslPasswordGuard(true, "a-real-secret");

        assertThatCode(guard::checkPassword).doesNotThrowAnyException();
    }

    @Test
    void should_notThrow_when_sslDisabledAndPasswordIsDevDefault() {
        SslPasswordGuard guard = new SslPasswordGuard(false, "mysuricates");

        assertThat(guard).isNotNull();
        guard.checkPassword();
    }
}
