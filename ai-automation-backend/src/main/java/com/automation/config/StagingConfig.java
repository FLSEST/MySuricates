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
}
