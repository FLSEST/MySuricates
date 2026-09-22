package com.automation.service.system;

import com.automation.config.InstanceAddress;
import com.automation.repository.AppSettingsRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
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
                log.warn("  AUTO-GENERATED API KEY: ****{}", generated.substring(generated.length() - 4));
                log.warn("  Full key: {}", apiKeyFile());
                log.warn("  Set AUTOMATION_API_KEY env var to use your own.");
                log.warn("========================================");
            }
        );
        writeApiKeyFile(cachedApiKey);
    }

    private static Path apiKeyFile() {
        return InstanceAddress.apiKeyFile();
    }

    private void writeApiKeyFile(String key) {
        Path apiKeyFile = apiKeyFile();
        try {
            if (key == null || key.isBlank()) {
                Files.deleteIfExists(apiKeyFile);
                return;
            }
            Files.createDirectories(apiKeyFile.getParent());
            Files.writeString(apiKeyFile, key);
            try {
                Files.setPosixFilePermissions(apiKeyFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) { }
            log.info("Wrote API key to {}", apiKeyFile);
        } catch (IOException e) {
            log.warn("Failed to write API key file: {}", e.getMessage());
        }
    }

    public String getEffectiveApiKey() {
        return cachedApiKey != null ? cachedApiKey : "";
    }

    public boolean matchesEffectiveApiKey(String candidate) {
        String effectiveApiKey = getEffectiveApiKey();
        if (candidate == null || candidate.isBlank() || effectiveApiKey.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                effectiveApiKey.getBytes(StandardCharsets.UTF_8));
    }
}
