package com.automation.service.integration;

import com.automation.repository.AppSettingsRepository;
import com.automation.util.SecretFiles;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.UnaryOperator;

@Service
@Slf4j
public class IntegrationEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final String SETTINGS_KEY = "integration-encryption-key";

    private final AppSettingsRepository appSettingsRepository;
    private final Path keyFile;
    private final UnaryOperator<String> envReader;
    private SecretKey secretKey;

    @Autowired
    public IntegrationEncryptionService(
            AppSettingsRepository appSettingsRepository,
            @Value("${automation.integration.key-file:${automation.data-home}/integration-encryption.key}") String keyFilePath) {
        this(appSettingsRepository, Path.of(keyFilePath), System::getenv);
    }

    IntegrationEncryptionService(AppSettingsRepository appSettingsRepository, Path keyFile, UnaryOperator<String> envReader) {
        this.appSettingsRepository = appSettingsRepository;
        this.keyFile = keyFile;
        this.envReader = envReader;
    }

    /**
     * Key resolution order: env var > key file > app_settings (legacy).
     *
     * A key found only in app_settings is migrated to the key file and then
     * removed from the database — key and ciphertext must not live in the same
     * SQLite file. The database is only written again if the key file cannot
     * be created (e.g. read-only home in a container); startup must not brick
     * and freshly generated keys must never be memory-only, or credentials
     * encrypted now would be lost on restart.
     */
    @PostConstruct
    void init() {
        String envKey = envReader.apply("AUTOMATION_INTEGRATION_KEY");
        if (envKey != null && !envKey.isBlank()) {
            secretKey = toKey(envKey);
            log.info("Integration encryption key loaded from environment");
            return;
        }

        String fromFile = readKeyFile();
        String fromDb = appSettingsRepository.get(SETTINGS_KEY).orElse(null);

        if (fromFile != null) {
            secretKey = toKey(fromFile);
            log.info("Integration encryption key loaded from {}", keyFile);
            if (fromDb != null) {
                if (fromDb.equals(fromFile)) {
                    appSettingsRepository.delete(SETTINGS_KEY);
                    log.info("Removed migrated integration encryption key from database");
                } else {
                    log.warn("Integration encryption key in database differs from {} — keeping both, using the file", keyFile);
                }
            }
            return;
        }

        if (fromDb != null) {
            secretKey = toKey(fromDb);
            try {
                writeKeyFile(fromDb);
                appSettingsRepository.delete(SETTINGS_KEY);
                log.info("Integration encryption key migrated from database to {}", keyFile);
            } catch (IOException e) {
                log.error("Failed to write integration encryption key to {} — key remains in database", keyFile, e);
            }
            return;
        }

        secretKey = generateKey();
        String encoded = Base64.getEncoder().encodeToString(secretKey.getEncoded());
        try {
            writeKeyFile(encoded);
            log.info("Integration encryption key generated and stored in {}", keyFile);
        } catch (IOException e) {
            appSettingsRepository.set(SETTINGS_KEY, encoded);
            log.error("Failed to write integration encryption key to {} — stored in database as fallback", keyFile, e);
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes());

            byte[] combined = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt token", e);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt token", e);
        }
    }

    private String readKeyFile() {
        try {
            if (!Files.isRegularFile(keyFile)) return null;
            String content = Files.readString(keyFile).trim();
            return content.isEmpty() ? null : content;
        } catch (IOException e) {
            log.error("Failed to read integration encryption key from {}", keyFile, e);
            return null;
        }
    }

    private void writeKeyFile(String base64Key) throws IOException {
        SecretFiles.writeOwnerOnly(keyFile, base64Key);
    }

    private static SecretKeySpec toKey(String base64) {
        return new SecretKeySpec(Base64.getDecoder().decode(base64), "AES");
    }

    private static SecretKey generateKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256);
            return keyGen.generateKey();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate encryption key", e);
        }
    }
}
