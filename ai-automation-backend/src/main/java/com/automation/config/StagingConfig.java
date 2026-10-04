package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "automation.staging")
@Getter
@Setter
public class StagingConfig {

    private String composeFile = "docker-compose.stable.yml";
    private String backendDir = "";
    private String frontendDir = "";
    private String containerName = "mysuricates-stable";
    private String stableFrontendDir = "stable-frontend";
    private String healthUrl = "http://localhost:9091/api/v1/system/health";
    private boolean autoStart = true;

    // Test instance properties
    private String testComposeFile = "docker-compose.test.yml";
    private String testContainerName = "mysuricates-test";
    private String testHealthUrl = "http://localhost:8090/api/v1/system/health";
    private boolean testAutoStart = true;

    /**
     * Sync targets of the staged setup ({@code scripts/setup.sh --mode staged}, TODO 84a78bb7): Dev knows
     * Test and Prod by base URL and a file holding the target's API key (written by
     * {@code scripts/instance.sh sync-keys}). Empty URL = target not configured.
     */
    private Targets targets = new Targets();

    @Getter
    @Setter
    public static class Targets {
        private Target test = new Target();
        private Target prod = new Target();
    }

    @Getter
    @Setter
    public static class Target {
        /** Base URL of the target instance, e.g. {@code http://test:9090}; {@code /api/v1} is optional. */
        private String url = "";
        /** File with the target's API key — read on every call, never from the target's database. */
        private String keyFile = "";
    }
}
