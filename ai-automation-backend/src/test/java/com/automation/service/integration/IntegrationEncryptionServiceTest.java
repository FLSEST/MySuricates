package com.automation.service.integration;

import com.automation.repository.AppSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IntegrationEncryptionServiceTest {

    private static final String SETTINGS_KEY = "integration-encryption-key";

    @Mock
    private AppSettingsRepository appSettingsRepository;

    @TempDir
    Path tempDir;

    private Path keyFile;
    private IntegrationEncryptionService service;

    @BeforeEach
    void setUp() {
        keyFile = tempDir.resolve("integration-encryption.key");
        when(appSettingsRepository.get(eq(SETTINGS_KEY))).thenReturn(Optional.empty());
        service = newService(Map.of());
        service.init();
    }

    private IntegrationEncryptionService newService(Map<String, String> env) {
        return new IntegrationEncryptionService(appSettingsRepository, keyFile, env::get);
    }

    @Test
    void encryptDecryptRoundtrip() {
        String plaintext = "my-secret-access-token-12345";
        String encrypted = service.encrypt(plaintext);

        assertThat(encrypted).isNotNull();
        assertThat(encrypted).isNotEqualTo(plaintext);

        String decrypted = service.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void encryptNullReturnsNull() {
        assertThat(service.encrypt(null)).isNull();
        assertThat(service.decrypt(null)).isNull();
    }

    @Test
    void differentPlaintextsProduceDifferentCiphertexts() {
        String encrypted1 = service.encrypt("token-one");
        String encrypted2 = service.encrypt("token-two");

        assertThat(encrypted1).isNotEqualTo(encrypted2);
    }

    @Test
    void sameTextEncryptedTwiceProducesDifferentCiphertexts() {
        // Due to random IV, same plaintext should produce different ciphertexts
        String text = "same-token";
        String encrypted1 = service.encrypt(text);
        String encrypted2 = service.encrypt(text);

        assertThat(encrypted1).isNotEqualTo(encrypted2);
        assertThat(service.decrypt(encrypted1)).isEqualTo(text);
        assertThat(service.decrypt(encrypted2)).isEqualTo(text);
    }

    @Test
    void generatesKeyIntoFileNotDatabaseOnFirstStart() throws Exception {
        assertThat(keyFile).isRegularFile();
        assertThat(Base64.getDecoder().decode(Files.readString(keyFile).trim())).hasSize(32);
        verify(appSettingsRepository, never()).set(anyString(), anyString());
    }

    /**
     * Regression guard for the 2026-08-06 bug: the integration test profile did not pin
     * {@code automation.integration.key-file}, so init() generated a key straight into the
     * developer's real {@code ~/.autotrigger/}. Here the constructor is given an explicit
     * @TempDir path (as production config now also resolves to a per-JVM temp path in tests),
     * so the generate-branch must write there — never under the real user.home.
     */
    @Test
    void init_generatesKeyAtConfiguredPath_notInHome() {
        assertThat(keyFile).isRegularFile();
        assertThat(keyFile.toAbsolutePath().toString())
                .doesNotStartWith(System.getProperty("user.home"));
    }

    @Test
    void keyFileIsOwnerOnly() throws Exception {
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(keyFile);
        assertThat(perms).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void loadsExistingKeyFromFile() {
        String encrypted = service.encrypt("test");

        IntegrationEncryptionService second = newService(Map.of());
        second.init();

        assertThat(second.decrypt(encrypted)).isEqualTo("test");
    }

    @Test
    void envKeyTakesPrecedenceOverFile() {
        String envKey = Base64.getEncoder().encodeToString(new byte[32]);
        IntegrationEncryptionService envService = newService(Map.of("AUTOMATION_INTEGRATION_KEY", envKey));
        envService.init();

        // File-based service and env-based service use different keys
        String encrypted = envService.encrypt("env-secret");
        assertThat(envService.decrypt(encrypted)).isEqualTo("env-secret");
        assertThat(Files.exists(keyFile)).isTrue();
    }

    @Test
    void migratesLegacyDatabaseKeyToFileAndDeletesDbEntry() throws Exception {
        Files.deleteIfExists(keyFile);
        String legacyKey = Base64.getEncoder().encodeToString(generateRawKey());
        when(appSettingsRepository.get(eq(SETTINGS_KEY))).thenReturn(Optional.of(legacyKey));

        IntegrationEncryptionService migrated = newService(Map.of());
        migrated.init();

        assertThat(Files.readString(keyFile).trim()).isEqualTo(legacyKey);
        verify(appSettingsRepository).delete(eq(SETTINGS_KEY));

        // Data encrypted under the legacy key must remain decryptable
        IntegrationEncryptionService reloaded = newService(Map.of());
        reloaded.init();
        String encrypted = migrated.encrypt("legacy-data");
        assertThat(reloaded.decrypt(encrypted)).isEqualTo("legacy-data");
    }

    @Test
    void removesDbCopyWhenFileAlreadyMigrated() throws Exception {
        String fileKey = Files.readString(keyFile).trim();
        when(appSettingsRepository.get(eq(SETTINGS_KEY))).thenReturn(Optional.of(fileKey));

        IntegrationEncryptionService second = newService(Map.of());
        second.init();

        verify(appSettingsRepository).delete(eq(SETTINGS_KEY));
    }

    @Test
    void keepsDivergingDbKeyAndUsesFile() throws Exception {
        String otherKey = Base64.getEncoder().encodeToString(generateRawKey());
        when(appSettingsRepository.get(eq(SETTINGS_KEY))).thenReturn(Optional.of(otherKey));

        String encrypted = service.encrypt("file-key-data");
        IntegrationEncryptionService second = newService(Map.of());
        second.init();

        verify(appSettingsRepository, never()).delete(anyString());
        assertThat(second.decrypt(encrypted)).isEqualTo("file-key-data");
    }

    @Test
    void fallsBackToDatabaseWhenKeyFileNotWritable() throws Exception {
        // Parent "directory" is a regular file → createDirectories fails
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        keyFile = blocker.resolve("integration-encryption.key");

        IntegrationEncryptionService fallback = newService(Map.of());
        fallback.init();

        verify(appSettingsRepository).set(eq(SETTINGS_KEY), anyString());
        String encrypted = fallback.encrypt("fallback-data");
        assertThat(fallback.decrypt(encrypted)).isEqualTo("fallback-data");
    }

    private static byte[] generateRawKey() throws Exception {
        javax.crypto.KeyGenerator keyGen = javax.crypto.KeyGenerator.getInstance("AES");
        keyGen.init(256);
        return keyGen.generateKey().getEncoded();
    }
}
