package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "automation.pool")
@Getter
@Setter
public class WorkflowPoolConfig {

    /**
     * Directory containing pool workflow JSON files.
     */
    private String directory = "workflow-pool";

    /**
     * Whether the workflow pool feature is enabled.
     */
    private boolean enabled = true;
}
