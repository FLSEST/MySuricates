package com.automation.service.staging;

import com.automation.config.StagingConfig;
import com.automation.dto.request.SyncDefinitionsRequest;
import com.automation.dto.response.SyncPreview;
import com.automation.dto.response.SyncResult;
import com.automation.dto.response.SyncTargetResponse;
import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedStateException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Definition sync of the staged setup ({@code scripts/setup.sh --mode staged}, TODO 84a78bb7): Dev
 * pushes its definitions to Test or Prod. It knows them from {@code automation.staging.targets} — a
 * base URL and a file with the target's API key, both written by the installer — and never reads a
 * key from the target's database. Prod receives only sealed definitions ({@link SealedSyncScope}).
 *
 * <p>Transport and the sync itself are the ones of the Stable/Test sync ({@link InstanceSyncHelper},
 * {@link SyncPreviewService}); only where the target is and how its key is found differ. No
 * transaction here: every call goes over the network.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StagedSyncService {

    /** The two targets the installer knows; Prod only takes sealed definitions. */
    enum Target {
        TEST(false), PROD(true);

        final boolean sealedOnly;

        Target(boolean sealedOnly) {
            this.sealedOnly = sealedOnly;
        }

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private record Resolved(Target target, String baseUrl, String apiKey) {
    }

    private final StagingConfig config;
    private final InstanceSyncHelper syncHelper;
    private final SyncPreviewService previewService;
    private final InstanceHttpClient httpClient;
    private final SealedSyncScope sealedScope;

    /** The configured targets with key and health state; an unconfigured one is left out. */
    public List<SyncTargetResponse> targets() {
        List<SyncTargetResponse> result = new ArrayList<>();
        for (Target target : Target.values()) {
            StagingConfig.Target cfg = settings(target);
            if (isBlank(cfg.getUrl())) {
                continue;
            }
            String key = readKey(cfg.getKeyFile());
            String baseUrl = apiBase(cfg.getUrl());
            JsonNode info = withKey(key, () -> httpClient.httpGet(baseUrl + "/system/info"));
            boolean reachable = info != null || healthy(baseUrl, key);
            String version = info != null ? info.path("version").asText(null) : null;
            result.add(new SyncTargetResponse(target.id(), cfg.getUrl(), key != null, reachable, target.sealedOnly, version));
        }
        return result;
    }

    public SyncPreview preview(String name) {
        Resolved r = resolve(name);
        SyncPreview preview = previewService.syncPreview(r.baseUrl(), r.apiKey());
        return r.target().sealedOnly ? sealedScope.restrict(preview) : preview;
    }

    public SyncResult sync(String name, SyncDefinitionsRequest request) {
        Resolved r = resolve(name);
        SyncDefinitionsRequest effective = r.target().sealedOnly ? sealedScope.restrict(request) : request;
        log.info("Definition sync to {} ({})", r.target().id(), r.baseUrl());
        return syncHelper.syncDefinitionsTo(r.baseUrl(), r.apiKey(), effective);
    }

    // ── Resolution ──────────────────────────────────────────

    private Resolved resolve(String name) {
        Target target = parse(name);
        StagingConfig.Target cfg = target == null ? null : settings(target);
        if (cfg == null || isBlank(cfg.getUrl())) {
            throw new LocalizedStateException(ErrorMessage.SYNC_TARGET_NOT_CONFIGURED, Map.of("target", String.valueOf(name)));
        }
        String key = readKey(cfg.getKeyFile());
        if (key == null) {
            throw new LocalizedStateException(ErrorMessage.SYNC_TARGET_KEY_MISSING,
                    Map.of("target", target.id(), "keyFile", isBlank(cfg.getKeyFile()) ? "(none)" : cfg.getKeyFile()));
        }
        String baseUrl = apiBase(cfg.getUrl());
        if (!healthy(baseUrl, key)) {
            throw new LocalizedStateException(ErrorMessage.SYNC_TARGET_UNREACHABLE, Map.of("target", target.id(), "url", cfg.getUrl()));
        }
        return new Resolved(target, baseUrl, key);
    }

    private static Target parse(String name) {
        for (Target target : Target.values()) {
            if (target.id().equals(name)) {
                return target;
            }
        }
        return null;
    }

    private StagingConfig.Target settings(Target target) {
        StagingConfig.Targets targets = config.getTargets();
        return target == Target.PROD ? targets.getProd() : targets.getTest();
    }

    private boolean healthy(String baseUrl, String key) {
        return withKey(key, () -> httpClient.httpGet(baseUrl + "/system/health")) != null;
    }

    /**
     * Runs a call with the target's key — or with none: an empty override keeps this instance's own
     * key from going to another instance when the target's key file is missing.
     */
    private <T> T withKey(String key, Supplier<T> call) {
        httpClient.setTargetApiKeyOverride(key == null ? "" : key);
        try {
            return call.get();
        } finally {
            httpClient.clearTargetApiKeyOverride();
        }
    }

    /** The key from the file, trimmed; {@code null} if there is none (no file, unreadable, empty). */
    static String readKey(String keyFile) {
        if (isBlank(keyFile)) {
            return null;
        }
        try {
            String key = Files.readString(Path.of(keyFile)).trim();
            return key.isEmpty() ? null : key;
        } catch (IOException | RuntimeException e) {
            log.debug("No sync key in {}: {}", keyFile, e.getMessage());
            return null;
        }
    }

    /** {@code http://test:9090}, {@code …/} and {@code …/api/v1} all mean the same API base. */
    static String apiBase(String url) {
        String base = url.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/api/v1") ? base : base + "/api/v1";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
