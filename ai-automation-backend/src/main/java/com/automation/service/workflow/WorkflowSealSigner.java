package com.automation.service.workflow;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Signs a seal with a key that is <b>not</b> in the database. Without it, {@code sealed_hash} lives
 * in the same SQLite file as the definition it protects: whoever can write that file changes both
 * and passes the check. The signature covers workflow id, definition hash and skill pins, so a seal
 * cannot be moved to another workflow either.
 *
 * <p>Key resolution like {@code IntegrationEncryptionService}: env {@code AUTOMATION_SEAL_KEY} >
 * key file (created with 0600 on first use) — never {@code app_settings}, which would put key and
 * signature back into one file. If no key can be obtained, signing is unavailable: seals are still
 * set (unsigned, as before) and {@code require-signed-seal} is what makes an unsigned seal refuse
 * its runs. Losing the key therefore does not brick anything — the seals must be set again.
 */
@Slf4j
@Component
public class WorkflowSealSigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String ENV_KEY = "AUTOMATION_SEAL_KEY";

    private final Path keyFile;
    private final UnaryOperator<String> envReader;
    private SecretKeySpec key;

    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowSealSigner(
            @Value("${automation.seal.key-file:${user.home}/.autotrigger/seal-signing.key}") String keyFilePath) {
        this(Path.of(keyFilePath), System::getenv);
    }

    WorkflowSealSigner(Path keyFile, UnaryOperator<String> envReader) {
        this.keyFile = keyFile;
        this.envReader = envReader;
    }

    @PostConstruct
    void init() {
        String fromEnv = envReader.apply(ENV_KEY);
        if (fromEnv != null && !fromEnv.isBlank()) {
            key = toKey(fromEnv);
            log.info("Seal signing key loaded from environment");
            return;
        }
        try {
            if (Files.exists(keyFile)) {
                String stored = Files.readString(keyFile).trim();
                if (!stored.isEmpty()) {
                    key = toKey(stored);
                    log.info("Seal signing key loaded from {}", keyFile);
                    return;
                }
            }
            key = toKey(writeNewKey());
            log.info("Seal signing key created at {}", keyFile);
        } catch (IOException | RuntimeException e) {
            // A read-only home must not stop the application; seals then stay unsigned and
            // require-signed-seal decides whether that is acceptable.
            log.warn("No seal signing key available ({}): seals will not be signed", e.toString());
        }
    }

    /** Whether seals can be signed at all — false when no key could be read or created. */
    public boolean available() {
        return key != null;
    }

    /** Signature over the sealed state, or {@code null} when no key is available. */
    public String sign(String workflowId, String definitionHash, Map<String, String> skillPins) {
        if (key == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return Base64.getEncoder().encodeToString(mac.doFinal(payload(workflowId, definitionHash, skillPins)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign seal: " + e.getMessage(), e);
        }
    }

    /** Constant-time check; false when no key is available or the signature does not match. */
    public boolean verify(String workflowId, String definitionHash, Map<String, String> skillPins, String signature) {
        if (key == null || signature == null) {
            return false;
        }
        String expected = sign(workflowId, definitionHash, skillPins);
        return expected != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] payload(String workflowId, String definitionHash, Map<String, String> skillPins) {
        // Canonical and unambiguous: fixed order, length-prefixed fields, pins as their canonical JSON.
        String pins = skillPins == null ? "" : WorkflowSkillPins.toJson(skillPins);
        String text = "v1|" + workflowId.length() + ":" + workflowId
                + "|" + (definitionHash == null ? 0 : definitionHash.length()) + ":" + (definitionHash == null ? "" : definitionHash)
                + "|" + pins.length() + ":" + pins;
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private String writeNewKey() throws IOException {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String encoded = Base64.getEncoder().encodeToString(raw);
        Files.createDirectories(keyFile.getParent());
        Files.writeString(keyFile, encoded);
        try {
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            log.debug("Filesystem without POSIX permissions — seal key file left at default permissions");
        }
        return encoded;
    }

    private static SecretKeySpec toKey(String encoded) {
        return new SecretKeySpec(Base64.getDecoder().decode(encoded.trim()), ALGORITHM);
    }
}
