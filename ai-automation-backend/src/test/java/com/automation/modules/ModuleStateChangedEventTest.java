package com.automation.modules;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListener;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleStateChangedEventTest {

    @Test
    void carriesSourceModuleAndNewState() {
        Object source = new Object();
        ModuleStateChangedEvent event = new ModuleStateChangedEvent(source, ModuleCatalog.WEBHOOKS, false);

        assertThat(event).isInstanceOf(ApplicationEvent.class);
        assertThat(event.getSource()).isSameAs(source);
        assertThat(event.getModule()).isEqualTo(ModuleCatalog.WEBHOOKS);
        assertThat(event.isEnabled()).isFalse();
    }

    @Test
    void reachesAnnotatedListenersThatFilterOnTheModule() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Listener.class)) {
            ctx.publishEvent(new ModuleStateChangedEvent(this, ModuleCatalog.AUDIT, true));
            ctx.publishEvent(new ModuleStateChangedEvent(this, ModuleCatalog.CHAINS, true));

            assertThat(ctx.getBean(Listener.class).received)
                    .singleElement()
                    .satisfies(e -> assertThat(e.getModule()).isEqualTo(ModuleCatalog.AUDIT));
        }
    }

    static class Listener {
        final List<ModuleStateChangedEvent> received = new ArrayList<>();

        @EventListener(condition = "#event.module.name() == 'AUDIT' && #event.enabled")
        void on(ModuleStateChangedEvent event) {
            received.add(event);
        }
    }
}
