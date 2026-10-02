package com.automation.service.ai;

import com.automation.config.AiProviderConfig;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.system.AppSettingChangedEvent;
import com.automation.testsupport.TurkishLocale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiProviderRegistryTest {

    @Mock
    private AiProviderConfig config;

    @Mock
    private AppSettingsRepository appSettingsRepository;

    @Mock
    private AiProvider claudeProvider;

    @Mock
    private AiProvider codexProvider;

    private AiProviderRegistry registry;

    @BeforeEach
    void setUp() {
        lenient().when(claudeProvider.getId()).thenReturn("CLAUDE");
        lenient().when(codexProvider.getId()).thenReturn("CODEX");
        lenient().when(config.getDefaultProvider()).thenReturn("CLAUDE");
        lenient().when(appSettingsRepository.get("default-ai-provider")).thenReturn(Optional.empty());

        registry = new AiProviderRegistry(
                List.of(claudeProvider, codexProvider), config, appSettingsRepository);
        registry.initCache();
    }

    /** 80796b2d: under tr-TR "pi".toUpperCase() is "Pİ" — the stored default named no provider any more. */
    @Test
    void defaultProviderFromSettings_doesNotDependOnTheDefaultLocale() {
        AiProvider piProvider = mock(AiProvider.class);
        when(piProvider.getId()).thenReturn("PI");
        when(appSettingsRepository.get("default-ai-provider")).thenReturn(Optional.of("pi"));
        AiProviderRegistry withPi = new AiProviderRegistry(
                List.of(claudeProvider, codexProvider, piProvider), config, appSettingsRepository);

        assertThat(TurkishLocale.call(() -> {
            withPi.initCache();
            return withPi.getDefaultProviderId();
        })).isEqualTo("PI");
        assertThat(TurkishLocale.call(() -> {
            withPi.refreshDefaultProviderCache();
            return withPi.resolve(null);
        })).isSameAs(piProvider);
    }

    @Nested
    class ResolveTests {

        @Test
        void resolve_withWorkflowProvider_returnsCorrectProvider() {
            AiProvider result = registry.resolve("CODEX");
            assertThat(result).isSameAs(codexProvider);
        }

        @Test
        void resolve_withLowercaseWorkflowProvider_resolvesUppercased() {
            AiProvider result = registry.resolve("codex");
            assertThat(result).isSameAs(codexProvider);
        }

        @Test
        void resolve_withNullWorkflowProvider_returnsCachedDefault() {
            AiProvider result = registry.resolve(null);
            assertThat(result).isSameAs(claudeProvider);
        }

        @Test
        void resolve_withBlankWorkflowProvider_returnsCachedDefault() {
            AiProvider result = registry.resolve("  ");
            assertThat(result).isSameAs(claudeProvider);
        }

        @Test
        void resolve_withUnknownProvider_fallsBackToDefault() {
            AiProvider result = registry.resolve("UNKNOWN_PROVIDER");
            assertThat(result).isSameAs(claudeProvider);
        }
    }

    @Nested
    class DefaultProviderTests {

        @Test
        void getDefault_returnsDefaultProvider() {
            AiProvider result = registry.getDefault();
            assertThat(result).isSameAs(claudeProvider);
        }

        @Test
        void getAvailableProviderIds_returnsAllRegistered() {
            List<String> ids = registry.getAvailableProviderIds();
            assertThat(ids).containsExactlyInAnyOrder("CLAUDE", "CODEX");
        }

        @Test
        void getDefaultProviderId_returnsCachedValue() {
            assertThat(registry.getDefaultProviderId()).isEqualTo("CLAUDE");
        }
    }

    @Nested
    class CacheTests {

        @Test
        void initCache_dbHasValidProvider_usesDbValue() {
            when(appSettingsRepository.get("default-ai-provider")).thenReturn(Optional.of("CODEX"));

            registry.initCache();

            assertThat(registry.getDefaultProviderId()).isEqualTo("CODEX");
            assertThat(registry.getDefault()).isSameAs(codexProvider);
        }

        @Test
        void initCache_dbHasUnknownProvider_usesConfigDefault() {
            when(appSettingsRepository.get("default-ai-provider")).thenReturn(Optional.of("UNKNOWN"));

            registry.initCache();

            assertThat(registry.getDefaultProviderId()).isEqualTo("CLAUDE");
        }

        @Test
        void initCache_dbEmpty_usesConfigDefault() {
            when(appSettingsRepository.get("default-ai-provider")).thenReturn(Optional.empty());

            registry.initCache();

            assertThat(registry.getDefaultProviderId()).isEqualTo("CLAUDE");
        }

        @Test
        void initCache_dbHasBlankValue_usesConfigDefault() {
            when(appSettingsRepository.get("default-ai-provider")).thenReturn(Optional.of("  "));

            registry.initCache();

            assertThat(registry.getDefaultProviderId()).isEqualTo("CLAUDE");
        }

        @Test
        void refreshDefaultProviderCache_updatesCache() {
            when(appSettingsRepository.get("default-ai-provider")).thenReturn(Optional.of("CODEX"));

            registry.refreshDefaultProviderCache();

            assertThat(registry.getDefaultProviderId()).isEqualTo("CODEX");
            assertThat(registry.resolve(null)).isSameAs(codexProvider);
        }

        @Test
        void onSettingChanged_defaultAiProvider_refreshesTheCache() {
            when(appSettingsRepository.get("default-ai-provider")).thenReturn(Optional.of("CODEX"));

            registry.onSettingChanged(new AppSettingChangedEvent("default-ai-provider"));

            assertThat(registry.getDefaultProviderId()).isEqualTo("CODEX");
        }

        @Test
        void onSettingChanged_otherKey_leavesTheCacheAlone() {
            registry.onSettingChanged(new AppSettingChangedEvent("suggestions-model"));

            verify(appSettingsRepository).get("default-ai-provider"); // once, from initCache in setUp
            assertThat(registry.getDefaultProviderId()).isEqualTo("CLAUDE");
        }
    }
}
