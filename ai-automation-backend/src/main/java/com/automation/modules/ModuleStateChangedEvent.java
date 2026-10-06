package com.automation.modules;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * A feature module was switched at runtime (Settings → Module, only with
 * {@code automation.modules.ui-editable=true}). Background paths of a module that otherwise run only at
 * startup can hook in here and catch up on their run.
 *
 * <p>Published only when the <em>effective</em> state has changed.
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
