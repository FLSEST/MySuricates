package com.automation.service.webhook;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.WebhookTrigger;
import com.automation.exception.ErrorCode;
import com.automation.exception.HardeningException;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.integration.IntegrationEncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Checks the HMAC-SHA256 signature of a webhook call over the raw request bytes. Applies to every
 * webhook that has a signing secret — independent of {@code automation.hardening.webhook-signatures},
 * which only gates setting and removing secrets: switching the flag off must not silently drop a
 * signature requirement (same rule as for seals).
 */
@Component
@RequiredArgsConstructor
public class WebhookSignatureVerifier {

    public static final String HEADER = "X-Signature";
    /** GitHub and Gitea send the same {@code sha256=<hex>} format under this name. */
    static final String GITHUB_HEADER = "X-Hub-Signature-256";
    private static final String PREFIX = "sha256=";

    private final IntegrationEncryptionService encryption;
    private final ActivityLogService activityLog;

    public void verify(WebhookTrigger webhook, byte[] rawBody, HttpHeaders headers) {
        if (webhook.getSigningSecretEnc() == null) {
            return;
        }
        String signature = headers.getFirst(HEADER) != null ? headers.getFirst(HEADER) : headers.getFirst(GITHUB_HEADER);
        if (matches(encryption.decrypt(webhook.getSigningSecretEnc()), rawBody, signature)) {
            return;
        }
        activityLog.warn(LogCategory.SECURITY, "Webhook call rejected — " + (signature == null ? "missing" : "invalid")
                        + " signature (workflow: " + webhook.getWorkflow().getName() + ")",
                "WebhookSignatureVerifier", webhook.getId());
        throw new HardeningException(ErrorCode.WEBHOOK_SIGNATURE_INVALID,
                "Missing or invalid " + HEADER + " header (expected sha256=<hex HMAC-SHA256 of the request body>)",
                Map.of("header", HEADER));
    }

    static boolean matches(String secret, byte[] body, String signature) {
        if (signature == null || !signature.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return false;
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signature.substring(PREFIX.length()).trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(hmacSha256(secret, body != null ? body : new byte[0]), provided);
    }

    static byte[] hmacSha256(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
