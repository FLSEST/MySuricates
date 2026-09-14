package com.automation.modules;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.handler.MappedInterceptor;

/**
 * Wires the module system.
 *
 * <p>The guard is registered as a {@link MappedInterceptor} bean (auto-detected by every handler
 * mapping) instead of a {@code WebMvcConfigurer}: {@code @WebMvcTest} slices pull in
 * {@code WebMvcConfigurer}s and {@code HandlerInterceptor} components but not plain
 * {@code @Configuration} classes, so the 38 controller slices keep working without a registry.
 */
@Slf4j
@Configuration
public class ModuleConfig {

    @Bean
    public ModuleRegistry moduleRegistry(Environment environment, ObjectProvider<ModuleOverrideStore> overrideStore) {
        ModuleRegistry registry = ModuleRegistry.fromEnvironment(environment);
        ModuleOverrideStore store = overrideStore.getIfAvailable();
        if (registry.uiEditable() && store != null) {
            applyStoredOverrides(registry, store);
        }
        log.info("Modules: {}", registry.summary());
        return registry;
    }

    /**
     * Settings-UI switches survive restarts. A stored set that no longer fits the startup
     * configuration (e.g. a dependency switched off in the file since) is skipped, not fatal —
     * the modules then follow the configuration until switched again.
     */
    static void applyStoredOverrides(ModuleRegistry registry, ModuleOverrideStore store) {
        try {
            registry.applyUiOverrides(store.load());
        } catch (RuntimeException e) {
            log.warn("Stored module switches from the settings UI were not applied: {}", e.getMessage());
        }
    }

    @Bean
    public MappedInterceptor moduleGuardInterceptor(ModuleRegistry moduleRegistry, ObjectMapper objectMapper) {
        return new MappedInterceptor(new String[]{"/api/**"}, null,
                new ModuleGuardInterceptor(moduleRegistry, objectMapper));
    }
}
