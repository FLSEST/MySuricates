package com.automation.service.dialog;

import com.automation.entity.BlockDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Text rules of the dialog import: which commands and remarks of a session carry no workflow value, how
 * the JSON is cut out of a model answer, and how repeated blocks collapse. Extracted from
 * {@link DialogConversionService} (SourceSizeRatchet, TODO 326d5987).
 */
final class DialogTextRules {

    private DialogTextRules() {
    }

    static List<BlockDefinition> deduplicate(List<BlockDefinition> blocks) {
        List<BlockDefinition> result = new ArrayList<>();
        BlockDefinition prev = null;
        for (BlockDefinition b : blocks) {
            if (prev == null || !b.getType().equals(prev.getType())
                    || !Objects.equals(b.getConfig(), prev.getConfig())) {
                result.add(b);
            }
            prev = b;
        }
        return result;
    }

    static boolean isInternalCommand(String cmd) {
        if (cmd == null || cmd.isBlank()) return true;
        // Typical internal/exploratory commands that have no value as a workflow block
        return cmd.startsWith("ls ") || cmd.equals("ls") || cmd.startsWith("cat ")
                || cmd.startsWith("echo ") || cmd.startsWith("pwd")
                || cmd.contains("| head ") || cmd.contains("| tail ");
    }

    static boolean isMetaCommentary(String text) {
        if (text == null) return true;
        String lower = text.strip().toLowerCase(Locale.ROOT);
        return lower.startsWith("let me") || lower.startsWith("i'll now")
                || lower.startsWith("i'm going to") || lower.startsWith("i can see")
                || lower.startsWith("i notice") || lower.startsWith("looking at");
    }

    static String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return text;
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
