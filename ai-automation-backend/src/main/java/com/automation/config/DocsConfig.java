package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "automation.docs")
@Getter
@Setter
public class DocsConfig {

    /**
     * Directory containing Markdown documentation files.
     */
    private String directory = "../docs";
}
