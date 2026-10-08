package com.automation.service.system;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.automation.config.InstanceAddress;
import com.automation.repository.AppSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    private AppSettingsRepository settingsRepo;

    private ApiKeyService service;

    @TempDir
    private Path tempHome;

    @BeforeEach
    void setUp() {
        // 88781724: the key file is configured, not derived from a redirected user.home
        service = new ApiKeyService(settingsRepo,
                new InstanceAddress("", "", 9090, tempHome.resolve("data/api-key").toString()));
        ReflectionTestUtils.setField(service, "configuredApiKey", "");
        ReflectionTestUtils.setField(service, "securityEnabled", true);
    }

    @Test
    void init_securityDisabled_doesNothing() {
        ReflectionTestUtils.setField(service, "securityEnabled", false);

        service.init();

        verifyNoInteractions(settingsRepo);
    }

    @Test
    void init_envVarSet_skipsDbKeyGeneration() {
        ReflectionTestUtils.setField(service, "configuredApiKey", "my-key");

        service.init();

        verifyNoInteractions(settingsRepo);
    }

    @Test
    void init_noKey_existsInDb_logsInfo() {
        when(settingsRepo.get("api-key")).thenReturn(Optional.of("db-key"));

        service.init();

        verify(settingsRepo).get("api-key");
        verify(settingsRepo, never()).set(any(), any());
    }

    @Test
    void init_noKey_notInDb_generatesAndStores() {
        when(settingsRepo.get("api-key")).thenReturn(Optional.empty());

        service.init();

        verify(settingsRepo).set(eq("api-key"), any(String.class));
    }

    @Test
    void init_generatedKey_logsOnlyMaskedSuffixAndPreservesStoredKey() throws Exception {
        when(settingsRepo.get("api-key")).thenReturn(Optional.empty());
        Logger logger = (Logger) LoggerFactory.getLogger(ApiKeyService.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            service.init();

            String key = service.getEffectiveApiKey();
            String messages = String.join("\n", appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage).toList());
            assertThat(messages)
                    .contains("****" + key.substring(key.length() - 4))
                    .doesNotContain(key)
                    .doesNotContain(key.substring(0, key.length() - 4));
            verify(settingsRepo).set("api-key", key);
            assertThat(Files.readString(tempHome.resolve("data/api-key"))).isEqualTo(key);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    /** TODO 94316c37: the key file never exists with the umask's permissions, not even briefly. */
    @Test
    void init_writesTheKeyFileOwnerOnly_andTightensAnOlderFileOthersCouldRead() throws Exception {
        Path keyFile = tempHome.resolve("data/api-key");
        Files.createDirectories(keyFile.getParent());
        Files.writeString(keyFile, "stale-key");
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-r--r--"));
        when(settingsRepo.get("api-key")).thenReturn(Optional.of("db-key"));

        service.init();

        assertThat(Files.readString(keyFile)).isEqualTo("db-key");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(keyFile))).isEqualTo("rw-------");
        try (var siblings = Files.list(keyFile.getParent())) {
            assertThat(siblings).containsExactly(keyFile); // no temp file left behind
        }
    }

    @Test
    void getEffectiveApiKey_envVarSet_returnsEnvVar() {
        ReflectionTestUtils.setField(service, "configuredApiKey", "env-key");
        service.init();

        assertThat(service.getEffectiveApiKey()).isEqualTo("env-key");
    }

    @Test
    void getEffectiveApiKey_noEnvVar_dbKeyExists_returnsDbKey() {
        when(settingsRepo.get("api-key")).thenReturn(Optional.of("db-key"));
        service.init();

        assertThat(service.getEffectiveApiKey()).isEqualTo("db-key");
    }

    @Test
    void getEffectiveApiKey_noEnvVar_noDbKey_generatesKey() {
        when(settingsRepo.get("api-key")).thenReturn(Optional.empty());
        service.init();

        assertThat(service.getEffectiveApiKey()).isNotBlank();
    }

    @Test
    void getEffectiveApiKey_doesNotReadDbAfterInit() {
        when(settingsRepo.get("api-key")).thenReturn(Optional.of("db-key"));
        service.init();
        clearInvocations(settingsRepo);

        for (int i = 0; i < 1000; i++) service.getEffectiveApiKey();

        verifyNoInteractions(settingsRepo);
    }

    @Test
    void matchesEffectiveApiKey_exactValue_returnsTrue() {
        ReflectionTestUtils.setField(service, "cachedApiKey", "sëcret-🔐");

        assertThat(service.matchesEffectiveApiKey("sëcret-🔐")).isTrue();
    }

    @Test
    void matchesEffectiveApiKey_differentValue_returnsFalse() {
        ReflectionTestUtils.setField(service, "cachedApiKey", "correct-key");

        assertThat(service.matchesEffectiveApiKey("wrong-key")).isFalse();
    }

    @Test
    void matchesEffectiveApiKey_nullOrBlankCandidate_returnsFalse() {
        ReflectionTestUtils.setField(service, "cachedApiKey", "correct-key");

        assertThat(service.matchesEffectiveApiKey(null)).isFalse();
        assertThat(service.matchesEffectiveApiKey("")).isFalse();
        assertThat(service.matchesEffectiveApiKey("   ")).isFalse();
    }

    @Test
    void matchesEffectiveApiKey_missingEffectiveKey_returnsFalse() {
        ReflectionTestUtils.setField(service, "cachedApiKey", "");

        assertThat(service.matchesEffectiveApiKey("candidate")).isFalse();
    }
}
