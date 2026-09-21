package com.automation.modules;

import com.automation.exception.ErrorCode;
import com.automation.exception.ModuleChangeException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModuleConfigTest {

    private final ModuleConfig config = new ModuleConfig();

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ModuleOverrideStore> provider(ModuleOverrideStore store) {
        ObjectProvider<ModuleOverrideStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return provider;
    }

    @Test
    void registryFollowsTheStartupEnvironment() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("automation.modules.preset", "research")
                .withProperty("automation.hardening.sealing", "true");

        ModuleRegistry registry = config.moduleRegistry(env, provider(null));

        assertThat(registry.preset()).isEqualTo(ModulePreset.RESEARCH);
        assertThat(registry.isEnabled(ModuleCatalog.CHAINS)).isTrue();
        assertThat(registry.isEnabled(ModuleCatalog.SEALING)).isTrue();
    }

    @Test
    void storedOverridesAreIgnoredUnlessUiEditable() {
        ModuleOverrideStore store = mock(ModuleOverrideStore.class);

        ModuleRegistry registry = config.moduleRegistry(new MockEnvironment(), provider(store));

        verifyNoInteractions(store);
        assertThat(registry.isEnabled(ModuleCatalog.WEBHOOKS)).isFalse();
    }

    @Test
    void storedOverridesSurviveARestartWhenUiEditable() {
        ModuleOverrideStore store = mock(ModuleOverrideStore.class);
        when(store.load()).thenReturn(Map.of(ModuleCatalog.WEBHOOKS, true));
        MockEnvironment env = new MockEnvironment().withProperty("automation.modules.ui-editable", "true");

        ModuleRegistry registry = config.moduleRegistry(env, provider(store));

        assertThat(registry.isEnabled(ModuleCatalog.WEBHOOKS)).isTrue();
        assertThat(registry.uiOverrides()).containsEntry(ModuleCatalog.WEBHOOKS, true);
    }

    @Test
    void uiEditableWithoutStoreStillStarts() {
        MockEnvironment env = new MockEnvironment().withProperty("automation.modules.ui-editable", "true");

        ModuleRegistry registry = config.moduleRegistry(env, provider(null));

        assertThat(registry.uiEditable()).isTrue();
        assertThat(registry.uiOverrides()).isEmpty();
    }

    @Test
    void storedSetThatNoLongerFitsIsSkippedNotFatal() {
        // wishes requires chains; the stored set enables wishes alone on a core instance
        ModuleRegistry registry = ModuleRegistry.of(Map.of("ui-editable", "true"), Map.of());
        ModuleOverrideStore store = mock(ModuleOverrideStore.class);
        when(store.load()).thenReturn(Map.of(ModuleCatalog.WISHES, true));

        ModuleConfig.applyStoredOverrides(registry, store);

        assertThat(registry.isEnabled(ModuleCatalog.WISHES)).isFalse();
        assertThat(registry.uiOverrides()).isEmpty();
    }

    @Test
    void failingStoreIsSkippedNotFatal() {
        ModuleRegistry registry = ModuleRegistry.of(Map.of("ui-editable", "true"), Map.of());
        ModuleOverrideStore store = mock(ModuleOverrideStore.class);
        when(store.load()).thenThrow(new ModuleChangeException(ErrorCode.STATE_CONFLICT, "broken"));

        ModuleConfig.applyStoredOverrides(registry, store);

        assertThat(registry.uiOverrides()).isEmpty();
    }

    @Test
    void guardInterceptorOnlyCoversTheApi() {
        MappedInterceptor interceptor = config.moduleGuardInterceptor(ModuleRegistry.allEnabled(), new ObjectMapper());

        assertThat(interceptor.getInterceptor()).isInstanceOf(ModuleGuardInterceptor.class);
        assertThat(interceptor.matches(request("/api/v1/chains"))).isTrue();
        assertThat(interceptor.matches(request("/actuator/health"))).isFalse();
        assertThat(interceptor.matches(request("/index.html"))).isFalse();
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        ServletRequestPathUtils.parseAndCache(request);
        return request;
    }
}
