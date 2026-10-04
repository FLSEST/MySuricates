package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;

import java.util.List;

/**
 * What {@link RunAdmission} asks before an unattended start (TODO e7e93fba): does the definition reach
 * into the platform itself — the instance's REST API, its key file or its database? Implemented by the
 * workflow analyzer; this interface keeps {@code service.workflow} free of an edge into the analyzer
 * package (which depends on {@code executor} and {@code service.audit}, both of which reach back here).
 */
public interface PlatformAccessCheck {

    /** A {@code HIGH} finding of a refusing rule: rule key, 0-based block index, English detail. */
    record Violation(String rule, int blockIndex, String detail) {
    }

    /** The violations in block order — empty when an unattended start is fine. Pattern rules only, no AI, no database. */
    List<Violation> unattendedViolations(List<BlockDefinition> blocks);
}
