package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "automation.backup")
@Getter
@Setter
public class BackupConfig {

    private boolean enabled = true;

    private String cron = "0 0 2 * * *";

    private int retainCount = 5;

    private String directory = System.getProperty("user.home") + "/.autotrigger/backups";
}
