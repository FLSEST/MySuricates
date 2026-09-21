package com.automation.modules;

import com.automation.controller.ChainController;
import com.automation.controller.WebhookController;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;

class RequiresModuleTest {

    @Test
    void isVisibleAtRuntimeOnTypesAndMethods() {
        // the guard interceptor reads it via reflection on each request
        assertThat(RequiresModule.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(RequiresModule.class.getAnnotation(Target.class).value())
                .containsExactlyInAnyOrder(ElementType.TYPE, ElementType.METHOD);
    }

    @Test
    void classLevelUsageNamesTheModule() {
        assertThat(ChainController.class.getAnnotation(RequiresModule.class).value()).isEqualTo(ModuleCatalog.CHAINS);
    }

    @Test
    void methodLevelUsageCoexistsWithTheClassLevelOne() throws Exception {
        assertThat(WebhookController.class.getAnnotation(RequiresModule.class).value())
                .isEqualTo(ModuleCatalog.WEBHOOKS);
        assertThat(WebhookController.class.getMethod("generateSigningSecret", String.class)
                .getAnnotation(RequiresModule.class).value())
                .isEqualTo(ModuleCatalog.WEBHOOK_SIGNATURES);
    }
}
