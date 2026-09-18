package com.automation.modules;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.event.ModuleStateChangedEvent;
import com.automation.exception.ModuleChangeException;
import com.automation.exception.ResourceNotFoundException;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ModuleOverrideServiceTest {

    @Mock private ModuleOverrideStore store;
    @Mock private ActivityLogService activityLog;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ModuleOverrideService service(ModuleRegistry registry) {
        return new ModuleOverrideService(registry, store, activityLog, eventPublisher);
    }

    @Test
    void anEffectiveStateChangeIsPublishedSoBackgroundPathsCanCatchUp() {
        ModuleRegistry registry = ModuleRegistry.of(Map.of("ui-editable", "true"), Map.of());

        service(registry).setEnabled("webhooks", true);

        ArgumentCaptor<ModuleStateChangedEvent> event = ArgumentCaptor.forClass(ModuleStateChangedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getModule()).isEqualTo(ModuleCatalog.WEBHOOKS);
        assertThat(event.getValue().isEnabled()).isTrue();
    }

    @Test
    void settingTheStateItAlreadyHasPublishesNothing() {
        ModuleRegistry registry = ModuleRegistry.of(Map.of("ui-editable", "true"), Map.of());

        service(registry).setEnabled("webhooks", false);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void enablingStoresAppliesAndLogsASecurityEvent() {
        ModuleRegistry registry = ModuleRegistry.of(Map.of("ui-editable", "true"), Map.of());

        ModuleRegistry.ModuleStatus status = service(registry).setEnabled("webhooks", true);

        assertThat(status.enabled()).isTrue();
        assertThat(status.overridden()).isTrue();
        assertThat(registry.isEnabled(ModuleCatalog.WEBHOOKS)).isTrue();
        verify(store).save(Map.of(ModuleCatalog.WEBHOOKS, true));
        verify(activityLog).warn(LogCategory.SECURITY,
                "Module 'webhooks' enabled in settings (off → on, startup configuration: off)", "ModuleOverrideService");
    }

    @Test
    void resetDropsTheOverride() {
        ModuleRegistry registry = ModuleRegistry.of(Map.of("ui-editable", "true"), Map.of());
        ModuleOverrideService service = service(registry);
        service.setEnabled("chains", true);

        ModuleRegistry.ModuleStatus status = service.resetToConfig("chains");

        assertThat(status.enabled()).isFalse();
        assertThat(registry.uiOverrides()).isEmpty();
        verify(store).save(Map.of());
    }

    @Test
    void refusalsStoreNothing() {
        ModuleOverrideService locked = service(ModuleRegistry.of(Map.of(), Map.of()));
        assertThatThrownBy(() -> locked.setEnabled("webhooks", true)).isInstanceOf(ModuleChangeException.class);

        ModuleOverrideService editable = service(ModuleRegistry.of(Map.of("ui-editable", "true"), Map.of()));
        assertThatThrownBy(() -> editable.setEnabled("wishes", true)).isInstanceOf(ModuleChangeException.class);
        assertThatThrownBy(() -> editable.setEnabled("nope", true)).isInstanceOf(ResourceNotFoundException.class);

        verify(store, never()).save(any());
        verifyNoInteractions(activityLog);
    }
}
