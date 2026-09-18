package com.automation.modules;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Ein Feature-Modul wurde zur Laufzeit umgeschaltet (Settings → Module, nur bei
 * {@code automation.modules.ui-editable=true}). Hintergrundpfade eines Moduls, die sonst nur beim
 * Start laufen, koennen sich hier einhaengen und ihren Lauf nachholen.
 *
 * <p>Wird nur veroeffentlicht, wenn sich der <em>effektive</em> Zustand geaendert hat.
 */
@Getter
public class ModuleStateChangedEvent extends ApplicationEvent {

    private final ModuleCatalog module;
    private final boolean enabled;

    public ModuleStateChangedEvent(Object source, ModuleCatalog module, boolean enabled) {
        super(source);
        this.module = module;
        this.enabled = enabled;
    }
}
