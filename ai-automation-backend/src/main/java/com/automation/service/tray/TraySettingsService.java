package com.automation.service.tray;

import com.automation.config.TrayConfig;
import com.automation.repository.AppSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Runtime settings for the host tray. Defaults deliberately keep desktop noise low. */
@Service
@RequiredArgsConstructor
public class TraySettingsService {

    static final String POPUPS_ENABLED = "tray.popups-enabled";
    static final String NOTIFY_APPROVALS = "tray.notify-approvals";
    static final String NOTIFY_FAILURES = "tray.notify-failures";
    static final String NOTIFY_SUCCESS = "tray.notify-success";
    static final String PUBLIC_URL = "tray.public-url";

    private final AppSettingsRepository settings;
    private final TrayConfig config;

    public record Preferences(boolean popupsEnabled,
                              boolean notifyApprovals,
                              boolean notifyFailures,
                              boolean notifySuccess,
                              String publicUrl) {
    }

    public Preferences current() {
        return new Preferences(
                booleanSetting(POPUPS_ENABLED, false),
                booleanSetting(NOTIFY_APPROVALS, true),
                booleanSetting(NOTIFY_FAILURES, true),
                booleanSetting(NOTIFY_SUCCESS, false),
                browserBaseUrl()
        );
    }

    public Map<String, Object> getCurrentSettings() {
        Preferences value = current();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("popupsEnabled", value.popupsEnabled());
        result.put("notifyApprovals", value.notifyApprovals());
        result.put("notifyFailures", value.notifyFailures());
        result.put("notifySuccess", value.notifySuccess());
        result.put("publicUrl", value.publicUrl());
        return result;
    }

    public Map<String, Object> update(Map<String, Object> values) {
        Map<String, String> validated = new LinkedHashMap<>();
        booleanValue(values, "popupsEnabled", POPUPS_ENABLED, validated);
        booleanValue(values, "notifyApprovals", NOTIFY_APPROVALS, validated);
        booleanValue(values, "notifyFailures", NOTIFY_FAILURES, validated);
        booleanValue(values, "notifySuccess", NOTIFY_SUCCESS, validated);

        if (values.containsKey("publicUrl")) {
            String publicUrl = validatePublicUrl(String.valueOf(values.get("publicUrl")));
            validated.put(PUBLIC_URL, publicUrl);
        }

        validated.forEach(settings::set);
        return getCurrentSettings();
    }

    public String browserBaseUrl() {
        String stored = settings.get(PUBLIC_URL).orElse("").trim();
        String configured = config.getPublicUrl() == null ? "" : config.getPublicUrl().trim();
        String fallback = config.getWebUiUrl() == null ? "" : config.getWebUiUrl().trim();
        return stripTrailingSlash(!stored.isEmpty() ? stored : !configured.isEmpty() ? configured : fallback);
    }

    void setPopupsEnabled(boolean enabled) {
        settings.set(POPUPS_ENABLED, Boolean.toString(enabled));
    }

    void setNotifyApprovals(boolean enabled) {
        settings.set(NOTIFY_APPROVALS, Boolean.toString(enabled));
    }

    void setNotifyFailures(boolean enabled) {
        settings.set(NOTIFY_FAILURES, Boolean.toString(enabled));
    }

    void setNotifySuccess(boolean enabled) {
        settings.set(NOTIFY_SUCCESS, Boolean.toString(enabled));
    }

    private boolean booleanSetting(String key, boolean defaultValue) {
        return settings.get(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    private static void booleanValue(Map<String, Object> values,
                                     String requestKey,
                                     String storageKey,
                                     Map<String, String> validated) {
        if (!values.containsKey(requestKey)) return;
        Object raw = values.get(requestKey);
        if (!(raw instanceof Boolean) && !"true".equalsIgnoreCase(String.valueOf(raw))
                && !"false".equalsIgnoreCase(String.valueOf(raw))) {
            throw new IllegalArgumentException(requestKey + " must be true or false");
        }
        validated.put(storageKey, String.valueOf(Boolean.parseBoolean(String.valueOf(raw))));
    }

    private static String validatePublicUrl(String raw) {
        String value = stripTrailingSlash(raw == null ? "" : raw.trim());
        if (value.isEmpty()) return value;

        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("publicUrl must be a valid URL");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || scheme == null) {
            throw new IllegalArgumentException("publicUrl must be an absolute URL");
        }
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        if (!"https".equalsIgnoreCase(scheme) && !(loopback && "http".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("publicUrl must use HTTPS (HTTP is only allowed for localhost)");
        }
        return value;
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
