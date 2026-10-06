package com.automation.service.export;

import com.automation.entity.BlockDefinition;

import java.util.List;

/**
 * Result of a format import: workflow name + ordered block list.
 * Deliberately not a finished {@code Workflow} — project assignment and persistence are done by the controller.
 */
public record ImportedWorkflow(String name, List<BlockDefinition> blocks) {
}
