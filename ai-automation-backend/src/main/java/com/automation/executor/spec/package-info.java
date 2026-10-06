/**
 * The block type specifications ({@link com.automation.executor.spec.BlockSpecs}) – a leaf package
 * without dependencies on purpose: wish, factory, dialog import, run conversion, workflow optimization
 * and the assistant all read it. Kept in the assistant's fetch package, it pulled that package, and
 * through it service.pool, into the large package cycle (TODO b7c56046). Keep it free of imports from
 * {@code com.automation}.
 */
package com.automation.executor.spec;
