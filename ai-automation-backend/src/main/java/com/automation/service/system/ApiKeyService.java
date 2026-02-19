package com.automation.service.system;

import com.automation.repository.AppSettingsRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyService {

    private final AppSettingsRepository settingsRepo;

    @Value("${automation.security.api-key:}")
    private String configuredApiKey;

    @Value("${automation.security.enabled:true}")
    private boolean securityEnabled;

    @PostConstruct
    public void init() {
        if (!securityEnabled) {
            return;
        }
        if (configuredApiKey.isBlank()) {
            settingsRepo.get("api-key").ifPresentOrElse(
                key -> log.info("Using stored API key from database"),
                () -> {
                    String generated = UUID.randomUUID().toString();
                    settingsRepo.set("api-key", generated);
                    log.warn("========================================");
                    log.warn("  AUTO-GENERATED API KEY: {}", generated);
                    log.warn("  Set AUTOMATION_API_KEY env var to use your own.");
                    log.warn("========================================");
                }
            );
        }
    }

    public String getEffectiveApiKey() {
        if (!configuredApiKey.isBlank()) {
            return configuredApiKey;
        }
        return settingsRepo.get("api-key").orElse("");
    }
}
