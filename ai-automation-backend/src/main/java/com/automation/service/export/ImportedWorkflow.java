package com.automation.service.export;

import com.automation.entity.BlockDefinition;

import java.util.List;

/**
 * Ergebnis eines Format-Imports: Workflow-Name + geordnete Block-Liste.
 * Bewusst kein fertiger {@code Workflow} — Projekt-Zuordnung und Persistenz macht der Controller.
 */
public record ImportedWorkflow(String name, List<BlockDefinition> blocks) {
}
