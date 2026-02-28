package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "automation.chain")
@Getter
@Setter
public class ChainConfig {

    private int workflowTimeoutMinutes = 180;
}
