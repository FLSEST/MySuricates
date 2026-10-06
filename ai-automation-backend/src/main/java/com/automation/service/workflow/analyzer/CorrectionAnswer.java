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
 * The answer of the correction round of {@link GeneratedWorkflowCheck}: read it, check it for the same structure and
 * fill in omitted block fields from the original (TODO 326d5987).
 */
@Slf4j
final class CorrectionAnswer {

    private CorrectionAnswer() {
    }

    /**
     * What the correction changed in the structure, or {@code null} if it stayed the same: same
     * number of top-level blocks, same type per position and per block the same nested positions
     * ({@link NestedBlocks}: {@code blocks}, {@code cases[].blocks}, {@code default}) - so the same number of
     * blocks in every container. The correction may change the types of nested blocks.
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
            if (nestedBefore.size() != nestedAfter.size()) {
                return "Block " + (i + 1) + ": verschachtelte Blöcke " + nestedBefore.size() + " → " + nestedAfter.size();
            }
            String moved = firstMoved(nestedBefore, nestedAfter);
            if (moved != null) {
                return "Block " + (i + 1) + ": verschachtelter Block verschoben (" + moved + ")";
            }
        }
        return null;
    }

    /** First position where two path lists of equal length diverge, as {@code old → new}; otherwise {@code null}. */
    private static String firstMoved(List<String> before, List<String> after) {
        for (int j = 0; j < before.size(); j++) {
            if (!before.get(j).equals(after.get(j))) {
                return before.get(j) + " → " + after.get(j);
            }
        }
        return null;
    }

    private static List<String> nestedPositions(BlockDefinition block) {
        List<String> paths = new ArrayList<>();
        NestedBlocks.visit(block.getConfig(), nested -> paths.add(nested.path()));
        return paths;
    }

    /** Takes over {@code name}, {@code skillIds} and {@code requiredCapabilities} per position where the correction omits them. */
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
     * Reads a block list from a model answer: a JSON array or an object with {@code blocks},
     * possibly in code fences ({@link BlockJsonReader#parseAnswer}, the same mapping as the factory).
     * Empty if nothing usable is in it.
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
