package com.automation.service.integration;

import com.automation.entity.IntegrationConnection;
import com.automation.repository.IntegrationConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrationCredentialCheckTest {

    @Mock private IntegrationConnectionRepository connectionRepository;
    @Mock private IntegrationRegistry registry;
    @Mock private IntegrationProvider provider;

    private IntegrationCredentialCheck check;

    @BeforeEach
    void setUp() {
        check = new IntegrationCredentialCheck(connectionRepository, registry);
        when(registry.getProvider("cal")).thenReturn(provider);
        lenient().when(provider.getOAuthConfig()).thenReturn(new OAuthConfig("https://auth.example/authorize",
                "https://auth.example/token", List.of(), "MYSURICATES_NONEXISTENT_ID", "MYSURICATES_NONEXISTENT_SECRET"));
    }

    private static IntegrationConnection row(String name, boolean withCredentials) {
        IntegrationConnection c = IntegrationConnection.builder().providerId("cal").connectionName(name).build();
        if (withCredentials) {
            c.setClientIdEncrypted("enc-id");
            c.setClientSecretEncrypted("enc-secret");
        }
        return c;
    }

    @Test
    void isConfigured_trueForAConnectionWithItsOwnCredentials() {
        when(connectionRepository.findByProviderIdAndConnectionName("cal", "default"))
                .thenReturn(Optional.of(row("default", true)));

        assertThat(check.isConfigured("cal", "default")).isTrue();
    }

    @Test
    void isConfigured_namedOAuthConnectionUsesTheDefaultApp() {
        when(provider.requiresOAuthFlow()).thenReturn(true);
        when(connectionRepository.findByProviderIdAndConnectionName("cal", "work")).thenReturn(Optional.empty());
        when(connectionRepository.findByProviderIdAndConnectionName("cal", "default"))
                .thenReturn(Optional.of(row("default", true)));

        assertThat(check.isConfigured("cal", "work")).isTrue();
    }

    @Test
    void isConfigured_namedTokenConnectionNeedsItsOwnCredentials() {
        when(provider.requiresOAuthFlow()).thenReturn(false);
        when(connectionRepository.findByProviderIdAndConnectionName("cal", "work")).thenReturn(Optional.empty());

        assertThat(check.isConfigured("cal", "work")).isFalse();
        verify(connectionRepository, never()).findByProviderIdAndConnectionName("cal", "default");
    }

    @Test
    void isConfigured_falseWithoutRowAndWithoutEnvVars() {
        when(connectionRepository.findByProviderIdAndConnectionName("cal", "default"))
                .thenReturn(Optional.of(row("default", false)));

        assertThat(check.isConfigured("cal", "default")).isFalse();
    }

    @Test
    void isDefaultConfigured_matchesIsConfigured_withoutAQuery() {
        assertThat(check.isDefaultConfigured("cal", List.of(row("default", true), row("work", false)))).isTrue();
        assertThat(check.isDefaultConfigured("cal", List.of(row("default", false), row("work", true)))).isFalse();
        assertThat(check.isDefaultConfigured("cal", List.of())).isFalse();
        verify(connectionRepository, never()).findByProviderIdAndConnectionName(any(), any());
    }
}
