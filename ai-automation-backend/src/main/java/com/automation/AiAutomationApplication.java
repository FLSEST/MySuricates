package com.automation;

import com.automation.config.RuntimeClasspathGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @EnableScheduling steht in config/SchedulingConfig — dort profil-gebunden (nicht unter "integration").
@SpringBootApplication
public class AiAutomationApplication {

    public static void main(String[] args) {
        // Fail fast before Spring touches anything: booting from target/classes (mvn spring-boot:run)
        // shares the directory with IDE and Maven builds and dies later with NoClassDefFoundError.
        RuntimeClasspathGuard.refusalReason(System.getProperty("java.class.path"),
                        RuntimeClasspathGuard.allowFlagFromRuntime())
                .ifPresent(reason -> {
                    System.err.println(reason);
                    System.exit(2);
                });

        System.setProperty("java.awt.headless", "false"); // Override JVM flag before Spring reads it
        SpringApplication app = new SpringApplication(AiAutomationApplication.class);
        app.setHeadless(false);
        app.run(args);
    }
}
