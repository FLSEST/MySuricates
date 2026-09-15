package com.automation.service.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

import static com.automation.service.dashboard.DashboardLayoutRules.MAX_NAME_LENGTH;
import static com.automation.service.dashboard.DashboardLayoutRules.SUPPORTED_FORMAT_VERSIONS;
import static com.automation.service.dashboard.DashboardLayoutRules.invalid;
import static com.automation.service.dashboard.DashboardLayoutRules.requireObject;
import static com.automation.service.dashboard.DashboardLayoutRules.requireOnlyFields;
import static com.automation.service.dashboard.DashboardLayoutRules.requiredId;

/**
 * Strict server-side check of a dashboard layout save or rename (spec E1). Rejects instead of
 * dropping: the frontend sanitizes before sending, so anything invalid here is a bug or a forged
 * request. The layout itself is checked and turned into canonical JSON by
 * DashboardLayoutCanonicalizer; the limits live in DashboardLayoutRules.
 */
@Component
public class DashboardLayoutValidator {

    private static final Set<String> SAVE_FIELDS = Set.of("deviceLabel", "formatVersion", "layout", "baseVersionId", "force");

    public ValidatedLayoutSave validateSave(JsonNode body) {
        requireObject(body, "body");
        requireOnlyFields(body, SAVE_FIELDS, "body");
        String deviceLabel = optionalLabel(body.get("deviceLabel"), "deviceLabel");
        if (deviceLabel == null) {
            throw invalid("deviceLabel is required");
        }
        JsonNode formatVersion = body.get("formatVersion");
        if (formatVersion == null || !formatVersion.isInt() || !SUPPORTED_FORMAT_VERSIONS.contains(formatVersion.intValue())) {
            throw invalid("formatVersion must be one of " + SUPPORTED_FORMAT_VERSIONS);
        }
        JsonNode base = body.get("baseVersionId");
        String baseVersionId = base == null || base.isNull() ? null : requiredId(base, "baseVersionId");
        JsonNode force = body.get("force");
        if (force != null && !force.isNull() && !force.isBoolean()) {
            throw invalid("force must be a boolean");
        }
        ObjectNode canonical = DashboardLayoutCanonicalizer.canonicalLayout(body.get("layout"));
        String content = DashboardLayoutCanonicalizer.write(canonical);
        return new ValidatedLayoutSave(deviceLabel, formatVersion.intValue(), baseVersionId,
                force != null && force.asBoolean(false), content, sha256Hex(content), canonical.get("views").size());
    }

    /** Name for a rename; {@code null} clears it. */
    public String validateRename(JsonNode body) {
        requireObject(body, "body");
        requireOnlyFields(body, Set.of("name"), "body");
        if (!body.has("name")) {
            throw invalid("name is required (null clears it)");
        }
        return optionalLabel(body.get("name"), "name");
    }

    /** Trimmed label of 1–80 characters without control characters; absent or null stays null. */
    private static String optionalLabel(JsonNode node, String path) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.isTextual() ? node.textValue().strip() : "";
        if (value.isEmpty() || value.length() > MAX_NAME_LENGTH || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid(path + " must have 1 to " + MAX_NAME_LENGTH + " characters without control characters");
        }
        return value;
    }

    static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
