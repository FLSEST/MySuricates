package com.automation.modules;

import com.automation.service.repair.AutoRepairService;
import com.automation.event.WorkflowRunCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.context.support.GenericApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The SpEL condition must really resolve the registry bean and skip listeners of disabled modules. */
class ModuleConditionsTest {

    record Ping(String payload) {}

    static class FactoryListener {
        final List<String> received = new ArrayList<>();

        @EventListener(condition = ModuleConditions.FACTORY)
        public void on(Ping ping) {
            received.add(ping.payload());
        }
    }

    @Test
    void listenerRunsWhenFactoryIsEnabled() {
        assertThat(publishWith(Map.of("factory", "true"))).containsExactly("hello");
    }

    @Test
    void listenerIsSkippedWhenFactoryIsDisabled() {
        assertThat(publishWith(Map.of())).isEmpty();
    }

    @Test
    void autoRepairListenerUsesTheFactoryCondition() throws NoSuchMethodException {
        EventListener annotation = AutoRepairService.class
                .getMethod("onWorkflowRunCompleted", WorkflowRunCompletedEvent.class)
                .getAnnotation(EventListener.class);

        assertThat(annotation.condition()).isEqualTo(ModuleConditions.FACTORY);
    }

    private List<String> publishWith(Map<String, String> modules) {
        try (GenericApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean("moduleRegistry", ModuleRegistry.class, () -> ModuleRegistry.of(modules, Map.of()));
            ctx.registerBean(FactoryListener.class);
            ctx.refresh();
            ctx.publishEvent(new Ping("hello"));
            return ctx.getBean(FactoryListener.class).received;
        }
    }
}
