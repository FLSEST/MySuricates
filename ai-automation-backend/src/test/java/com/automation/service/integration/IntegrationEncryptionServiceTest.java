package com.automation.service.integration;

import com.automation.repository.AppSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IntegrationEncryptionServiceTest {

    @Mock
    private AppSettingsRepository appSettingsRepository;

    private IntegrationEncryptionService service;

    @BeforeEach
    void setUp() {
        when(appSettingsRepository.get(eq("integration-encryption-key"))).thenReturn(Optional.empty());
        doNothing().when(appSettingsRepository).set(anyString(), anyString());
        service = new IntegrationEncryptionService(appSettingsRepository);
        service.init();
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
    void generatesAndStoresKeyOnFirstStart() {
        verify(appSettingsRepository).set(eq("integration-encryption-key"), anyString());
    }

    @Test
    void loadsExistingKeyFromDatabase() {
        // Create a service that generates a key
        IntegrationEncryptionService first = service;
        String encrypted = first.encrypt("test");

        // Capture the stored key
        var keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(appSettingsRepository).set(eq("integration-encryption-key"), keyCaptor.capture());
        String storedKey = keyCaptor.getValue();

        // Create a new service that loads the existing key
        when(appSettingsRepository.get(eq("integration-encryption-key"))).thenReturn(Optional.of(storedKey));
        IntegrationEncryptionService second = new IntegrationEncryptionService(appSettingsRepository);
        second.init();

        // Should be able to decrypt what the first service encrypted
        assertThat(second.decrypt(encrypted)).isEqualTo("test");
    }
}
