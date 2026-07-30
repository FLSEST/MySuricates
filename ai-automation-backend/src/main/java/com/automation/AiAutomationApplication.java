package com.automation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @EnableScheduling steht in config/SchedulingConfig — dort profil-gebunden (nicht unter "integration").
@SpringBootApplication
public class AiAutomationApplication {

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "false"); // Override JVM flag before Spring reads it
        SpringApplication app = new SpringApplication(AiAutomationApplication.class);
        app.setHeadless(false);
        app.run(args);
    }
}
