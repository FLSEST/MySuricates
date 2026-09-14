package com.automation.modules;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller (or a single handler method) as belonging to an optional module.
 * When the module is disabled, {@link ModuleGuardInterceptor} answers 404 {@code MODULE_DISABLED}.
 * A method-level annotation wins over the class-level one.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiresModule {
    ModuleCatalog value();
}
