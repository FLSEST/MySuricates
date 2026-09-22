package com.automation.controller;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The onboarding endpoints on core controllers belong to the module (TODO 644fd5ad): with it off
 * they answer 404 MODULE_DISABLED like the profile endpoints, instead of writing files into projects.
 */
class OnboardingEndpointsGatedTest {

    @Test
    void projectOnboardingEndpointsRequireTheModule() {
        List<Method> onboarding = Arrays.stream(ProjectController.class.getDeclaredMethods())
                .filter(m -> m.getName().toLowerCase().contains("onboarding"))
                .toList();

        assertThat(onboarding).hasSize(5);
        assertThat(onboarding).allSatisfy(OnboardingEndpointsGatedTest::requiresOnboardingModule);
    }

    @Test
    void theIntegrationSnippetRequiresTheModule() throws Exception {
        Method snippet = SystemAccessController.class.getDeclaredMethod("integrationSnippet", String.class, String.class);

        requiresOnboardingModule(snippet);
    }

    private static void requiresOnboardingModule(Method method) {
        RequiresModule gate = method.getAnnotation(RequiresModule.class);
        assertThat(gate).as(method.getName()).isNotNull();
        assertThat(gate.value()).as(method.getName()).isEqualTo(ModuleCatalog.ONBOARDING_PROFILES);
    }
}
