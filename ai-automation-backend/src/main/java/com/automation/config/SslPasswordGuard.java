package com.automation.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to boot with SSL enabled while the keystore password is still the documented dev
 * default (docs/GETTING-STARTED.md §7, scripts/generate-dev-cert.sh). That default is a known,
 * public value for a self-signed dev certificate — fine while SSL is off, not fine once
 * AUTOMATION_SSL_ENABLED=true actually serves HTTPS with it.
 */
@Component
public class SslPasswordGuard {

    static final String DEV_DEFAULT_PASSWORD = "mysuricates";

    private final boolean sslEnabled;
    private final String keyStorePassword;

    public SslPasswordGuard(
            @Value("${server.ssl.enabled:false}") boolean sslEnabled,
            @Value("${server.ssl.key-store-password:}") String keyStorePassword) {
        this.sslEnabled = sslEnabled;
        this.keyStorePassword = keyStorePassword;
    }

    @PostConstruct
    public void checkPassword() {
        if (!sslEnabled) {
            return;
        }
        if (keyStorePassword == null || keyStorePassword.isBlank()) {
            throw new IllegalStateException(
                    "AUTOMATION_SSL_ENABLED=true requires AUTOMATION_SSL_PASSWORD (keystore password is empty)");
        }
        if (DEV_DEFAULT_PASSWORD.equals(keyStorePassword)) {
            throw new IllegalStateException(
                    "AUTOMATION_SSL_ENABLED=true requires a non-default AUTOMATION_SSL_PASSWORD "
                            + "(dev default 'mysuricates' refused)");
        }
    }
}
