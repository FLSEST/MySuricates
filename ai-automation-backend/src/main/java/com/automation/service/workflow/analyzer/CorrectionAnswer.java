package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.executor.NestedBlocks;
import com.automation.util.JsonRepairUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     * gern in Code-Fences. Leer, wenn nichts Brauchbares darin steht.
     */
    static List<BlockDefinition> parse(String response, ObjectMapper objectMapper) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        try {
            String text = stripFences(response.trim());
            int arr = text.indexOf('[');
            int obj = text.indexOf('{');
            JsonNode node;
            if (arr >= 0 && (obj < 0 || arr < obj)) {
                node = JsonRepairUtil.readTreeWithRepair(text.substring(arr, text.lastIndexOf(']') + 1));
            } else if (obj >= 0) {
                node = JsonRepairUtil.readTreeWithRepair(text.substring(obj, text.lastIndexOf('}') + 1)).path("blocks");
            } else {
                return List.of();
            }
            if (!node.isArray()) {
                return List.of();
            }
            List<BlockDefinition> blocks = new ArrayList<>();
            for (JsonNode b : node) {
                if (!b.isObject()) {
                    return List.of();
                }
                blocks.add(toBlock(b, objectMapper));
            }
            return blocks;
        } catch (Exception e) {
            log.debug("Baufehler-Korrektur: Antwort nicht lesbar: {}", e.getMessage());
            return List.of();
        }
    }

    private static BlockDefinition toBlock(JsonNode b, ObjectMapper objectMapper) {
        BlockDefinition block = new BlockDefinition();
        block.setType(b.path("type").asText("simple"));
        if (b.hasNonNull("name")) {
            block.setName(b.get("name").asText());
        }
        if (b.path("config").isObject()) {
            block.setConfig(objectMapper.convertValue(b.get("config"), new TypeReference<LinkedHashMap<String, Object>>() {}));
        }
        if (b.path("requiredCapabilities").isArray()) {
            block.setRequiredCapabilities(objectMapper.convertValue(b.get("requiredCapabilities"), new TypeReference<>() {}));
        }
        if (b.path("skillIds").isArray()) {
            block.setSkillIds(objectMapper.convertValue(b.get("skillIds"), new TypeReference<>() {}));
        }
        return block;
    }

    private static String stripFences(String text) {
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return text;
    }
}
