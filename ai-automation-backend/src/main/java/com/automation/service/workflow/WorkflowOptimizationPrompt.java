package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.executor.spec.BlockSpecs;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The prompt with which {@link WorkflowOptimizationService} asks an AI to improve a workflow. The
 * block schema comes from {@link BlockSpecs}, for the usual types and every further type the workflow
 * uses. The old text described a schema of its own, left out shell and claimed that blocks run in
 * Docker containers via the Claude CLI (TODO b07704ca); {@code PromptBlockSchemaGateTest} checks every
 * type and config key the text names.
 */
public final class WorkflowOptimizationPrompt {

    /** The block types an optimization may use; the types of the workflow itself come on top. */
    static final List<String> BLOCK_TYPES = List.of(
            "simple", "shell", "retry", "conditional", "foreach", "while", "agentic", "approval", "sub-workflow");

    private WorkflowOptimizationPrompt() {
    }

    /** {@code blocksJson}: the workflow's blocks as JSON, as the AI is to rewrite them. */
    public static String build(Workflow workflow, String blocksJson) {
        return """
                Du bist ein Workflow-Optimierer fuer die MySuricates-Plattform.

                ## Plattform-Kontext
                - Ein Workflow ist eine Folge von Bloecken. KI-Bloecke rufen den KI-Anbieter des Workflows auf dem Host \
                auf; shell-Befehle laufen auf dem Host, mit `container_image` in einem Docker-Container
                - Variablen: {{ variable }} werden vor Ausfuehrung durch Nutzereingaben ersetzt
                - {{ previous_output }} enthaelt den Output des vorherigen Blocks

                ## Block-Referenz
                Nur diese Typen und Config-Schluessel, genau so geschrieben: einen Block anderen Typs lehnt das \
                Speichern ab, andere Schluessel ignorieren die Executoren.

                %s## Dein Auftrag
                Analysiere den folgenden Workflow und optimiere ihn. Beachte:
                1. DEFENSIV: Aendere nur was eine klare Verbesserung ist
                2. VERIFIZIERE: Stelle sicher dass {{ Variablen }} erhalten bleiben
                3. ERKLAERE: Begruende jede Aenderung
                4. ERHALTE: Behalte die Kernlogik bei, aendere nicht den Zweck

                ## Aktueller Workflow
                Name: %s
                Beschreibung: %s
                Instructions: %s
                Blocks: %s

                ## Antwort-Format (NUR valides JSON)
                {
                  "suggestedName": "...",
                  "suggestedDescription": "...",
                  "suggestedInstructions": "...",
                  "blocks": [...],
                  "changes": [...]
                }
                `blocks` enthaelt alle Bloecke des optimierten Workflows, jeder mit `type` und `config` wie in der \
                Block-Referenz. Jeder Eintrag in `changes` hat `type` (modified, added, removed oder reordered) und \
                `description` (was und warum).
                """.formatted(
                BlockSpecs.reference(blockTypes(workflow.getBlocks())),
                workflow.getName() != null ? workflow.getName() : "",
                workflow.getDescription() != null ? workflow.getDescription() : "",
                workflow.getInstructions() != null ? workflow.getInstructions() : "",
                blocksJson
        );
    }

    /** {@link #BLOCK_TYPES}, then every further type of {@code blocks} that has a specification. */
    static List<String> blockTypes(List<BlockDefinition> blocks) {
        Set<String> types = new LinkedHashSet<>(BLOCK_TYPES);
        if (blocks != null) {
            blocks.stream().map(BlockDefinition::getType)
                    .filter(type -> type != null && BlockSpecs.types().contains(type))
                    .forEach(types::add);
        }
        return List.copyOf(types);
    }
}
