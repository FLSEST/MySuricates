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

    /** Cached in-memory — loaded once at startup, never re-read per request. */
    private volatile String cachedApiKey;

    @PostConstruct
    public void init() {
        if (!securityEnabled) {
            cachedApiKey = "";
            return;
        }
        if (!configuredApiKey.isBlank()) {
            cachedApiKey = configuredApiKey;
            return;
        }
        settingsRepo.get("api-key").ifPresentOrElse(
            key -> {
                cachedApiKey = key;
                log.info("Using stored API key from database");
            },
            () -> {
                String generated = UUID.randomUUID().toString();
                settingsRepo.set("api-key", generated);
                cachedApiKey = generated;
                log.warn("========================================");
                log.warn("  AUTO-GENERATED API KEY: {}", generated);
                log.warn("  Set AUTOMATION_API_KEY env var to use your own.");
                log.warn("========================================");
            }
        );
    }

    public String getEffectiveApiKey() {
        return cachedApiKey != null ? cachedApiKey : "";
    }
}
