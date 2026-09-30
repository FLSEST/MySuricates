package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.executor.NestedBlocks;
import com.automation.util.BlockJsonReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Die Antwort der Korrekturrunde von {@link GeneratedWorkflowCheck}: lesen, auf gleiche Struktur prüfen und
 * weggelassene Blockfelder aus dem Original ergänzen (TODO 326d5987).
 */
@Slf4j
final class CorrectionAnswer {

    private CorrectionAnswer() {
    }

    /**
     * Was die Korrektur an der Struktur geändert hat, oder {@code null}, wenn sie gleich geblieben ist: gleiche
     * Zahl der Top-Level-Blöcke, gleicher Typ je Position und je Block dieselben verschachtelten Positionen
     * ({@link NestedBlocks}: {@code blocks}, {@code cases[].blocks}, {@code default}) — also gleich viele
     * Blöcke in jedem Container. Die Typen verschachtelter Blöcke darf die Korrektur ändern.
     */
    static String shapeChange(List<BlockDefinition> original, List<BlockDefinition> corrected) {
        if (original.size() != corrected.size()) {
            return "Blockzahl " + original.size() + " → " + corrected.size();
        }
        for (int i = 0; i < original.size(); i++) {
            BlockDefinition a = original.get(i);
            BlockDefinition b = corrected.get(i);
            if (!Objects.equals(a.getType(), b.getType())) {
                return "Block " + (i + 1) + ": Typ " + a.getType() + " → " + b.getType();
            }
            List<String> nestedBefore = nestedPositions(a);
            List<String> nestedAfter = nestedPositions(b);
            if (!nestedBefore.equals(nestedAfter)) {
                return "Block " + (i + 1) + ": verschachtelte Blöcke " + nestedBefore.size() + " → " + nestedAfter.size();
            }
        }
        return null;
    }

    private static List<String> nestedPositions(BlockDefinition block) {
        List<String> paths = new ArrayList<>();
        NestedBlocks.visit(block.getConfig(), nested -> paths.add(nested.path()));
        return paths;
    }

    /** Übernimmt je Position {@code name}, {@code skillIds} und {@code requiredCapabilities}, wo die Korrektur sie weglässt. */
    static void keepOmittedFields(List<BlockDefinition> original, List<BlockDefinition> corrected) {
        for (int i = 0; i < original.size(); i++) {
            BlockDefinition a = original.get(i);
            BlockDefinition b = corrected.get(i);
            if ((b.getName() == null || b.getName().isBlank()) && a.getName() != null) {
                b.setName(a.getName());
            }
            if ((b.getSkillIds() == null || b.getSkillIds().isEmpty()) && a.getSkillIds() != null) {
                b.setSkillIds(new ArrayList<>(a.getSkillIds()));
            }
            if ((b.getRequiredCapabilities() == null || b.getRequiredCapabilities().isEmpty())
                    && a.getRequiredCapabilities() != null) {
                b.setRequiredCapabilities(new ArrayList<>(a.getRequiredCapabilities()));
            }
        }
    }

    /**
     * Liest eine Blockliste aus einer Modellantwort: ein JSON-Array oder ein Objekt mit {@code blocks},
     * gern in Code-Fences ({@link BlockJsonReader#parseAnswer}, dieselbe Abbildung wie die Factory).
     * Leer, wenn nichts Brauchbares darin steht.
     */
    static List<BlockDefinition> parse(String response, ObjectMapper objectMapper) {
        try {
            return BlockJsonReader.parseAnswer(response, objectMapper);
        } catch (Exception e) {
            log.debug("Baufehler-Korrektur: Antwort nicht lesbar: {}", e.getMessage());
            return List.of();
        }
    }
}
