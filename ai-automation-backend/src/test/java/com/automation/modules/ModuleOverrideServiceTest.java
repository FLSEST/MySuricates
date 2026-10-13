package com.automation.modules;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.exception.ModuleChangeException;
import com.automation.exception.ResourceNotFoundException;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
    void anotherToggleGetsThroughWhileAListenerOfTheFirstRuns() throws Exception {
        // Listeners run synchronously (the audit migration walks every scope); holding the toggle lock
        // meanwhile pinned the carrier of the virtual request thread and blocked every other toggle.
        ModuleRegistry registry = ModuleRegistry.of(Map.of("ui-editable", "true"), Map.of());
        ModuleOverrideService service = service(registry);
        AtomicReference<String> probe = new AtomicReference<>("listener not called");
        doAnswer(inv -> {
            ModuleStateChangedEvent event = inv.getArgument(0);
            if (event.getModule() == ModuleCatalog.WEBHOOKS) {
                probe.set(toggleFromAnotherThread(service, "chains"));
            }
            return null;
        }).when(eventPublisher).publishEvent(any(ApplicationEvent.class));

        service.setEnabled("webhooks", true);

        assertThat(probe.get()).isEqualTo("done");
        assertThat(registry.uiOverrides()).isEqualTo(Map.of(ModuleCatalog.WEBHOOKS, true, ModuleCatalog.CHAINS, true));
    }

    @Test
    void anotherToggleGetsThroughWhileTheActivityLogIsWritten() throws Exception {
        ModuleRegistry registry = ModuleRegistry.of(Map.of("ui-editable", "true"), Map.of());
        ModuleOverrideService service = service(registry);
        AtomicReference<String> probe = new AtomicReference<>("activity log not written");
        doAnswer(inv -> {
            if (inv.<String>getArgument(1).startsWith("Module 'webhooks'")) {
                probe.set(toggleFromAnotherThread(service, "chains"));
            }
            return null;
        }).when(activityLog).warn(eq(LogCategory.SECURITY), anyString(), eq("ModuleOverrideService"));

        service.setEnabled("webhooks", true);

        assertThat(probe.get()).isEqualTo("done");
    }

    @Test
    void concurrentTogglesLoseNoChangeAndStoreWhatTheRegistryApplies() throws Exception {
        ModuleRegistry registry = ModuleRegistry.of(Map.of("ui-editable", "true"), Map.of());
        ModuleOverrideService service = service(registry);
        AtomicReference<Map<ModuleCatalog, Boolean>> stored = new AtomicReference<>();
        doAnswer(inv -> {
            Map<ModuleCatalog, Boolean> overrides = inv.getArgument(0);
            Thread.sleep(2); // widens the window between reading the overrides and applying them
            stored.set(Map.copyOf(overrides));
            return null;
        }).when(store).save(any());
        List<String> modules = List.of("chains", "assistant", "learning", "memory", "audit", "bpmn", "webhooks",
                "terminal");

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ModuleRegistry.ModuleStatus>> toggles = modules.stream()
                    .map(name -> pool.submit(() -> {
                        start.await();
                        return service.setEnabled(name, true);
                    }))
                    .toList();
            start.countDown();
            for (Future<ModuleRegistry.ModuleStatus> toggle : toggles) {
                assertThat(toggle.get(30, TimeUnit.SECONDS).enabled()).isTrue();
            }
        }

        assertThat(registry.uiOverrides()).hasSize(modules.size()).doesNotContainValue(false);
        assertThat(stored.get()).isEqualTo(registry.uiOverrides());
    }

    /**
     * Toggles a module on another thread and waits a bounded time: {@code "done"} if it got through,
     * {@code "blocked"} if it is still waiting for the lock the calling toggle holds.
     */
    private static String toggleFromAnotherThread(ModuleOverrideService service, String module) throws Exception {
        ExecutorService other = Executors.newVirtualThreadPerTaskExecutor();
        try {
            other.submit(() -> service.setEnabled(module, true)).get(5, TimeUnit.SECONDS);
            return "done";
        } catch (TimeoutException e) {
            return "blocked";
        } finally {
            other.shutdown();
        }
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
