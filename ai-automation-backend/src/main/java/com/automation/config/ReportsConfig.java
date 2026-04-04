package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "automation.reports")
@Getter
@Setter
public class ReportsConfig {

    /**
     * Directory containing Markdown report files.
     */
    private String directory = "../reports";
}
