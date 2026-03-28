package com.automation.service.system;

import com.automation.repository.AppSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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

    @BeforeEach
    void setUp() {
        service = new ApiKeyService(settingsRepo);
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
}
