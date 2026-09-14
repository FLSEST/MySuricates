package com.automation.modules;

import com.automation.repository.AppSettingsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModuleOverrideStoreTest {

    @Mock private AppSettingsRepository settings;
    private ModuleOverrideStore store;

    @BeforeEach
    void setUp() {
        store = new ModuleOverrideStore(settings, new ObjectMapper());
    }

    @Test
    void roundTripsByModuleKey() {
        Map<ModuleCatalog, Boolean> overrides = new EnumMap<>(Map.of(ModuleCatalog.REMOTE_ACCESS, true, ModuleCatalog.CHAINS, false));

        store.save(overrides);

        verify(settings).set(ModuleOverrideStore.SETTINGS_KEY, "{\"chains\":false,\"remote-access\":true}");
    }

    @Test
    void emptyOverridesDeleteTheSetting() {
        store.save(Map.of());

        verify(settings).delete(ModuleOverrideStore.SETTINGS_KEY);
    }

    @Test
    void unknownModulesAndGarbageAreSkipped() {
        when(settings.get(ModuleOverrideStore.SETTINGS_KEY)).thenReturn(Optional.of("{\"webhooks\":true,\"gone\":true}"));
        assertThat(store.load()).containsExactly(Map.entry(ModuleCatalog.WEBHOOKS, true));

        when(settings.get(ModuleOverrideStore.SETTINGS_KEY)).thenReturn(Optional.of("not json"));
        assertThat(store.load()).isEmpty();
    }
}
