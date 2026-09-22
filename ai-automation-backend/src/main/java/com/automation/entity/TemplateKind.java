package com.automation.entity;

/**
 * What a built-in template is for (TODO 268ae0db).
 *
 * <ul>
 * <li>{@link #TEMPLATE}: a finished task you adapt (code quality, deploy …).</li>
 * <li>{@link #BLUEPRINT}: an empty scaffold with runnable placeholders that shows how block types
 *     connect (form → branch, loop with approval …). Shipped under {@code templates/blueprints/}.</li>
 * </ul>
 */
public enum TemplateKind {
    TEMPLATE,
    BLUEPRINT
}
