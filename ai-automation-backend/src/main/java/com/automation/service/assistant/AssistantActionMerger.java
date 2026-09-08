package com.automation.service.assistant;

import com.automation.dto.AssistantChatResponse.AssistantAction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges the two sources of assistant proposals: what the main model emitted inline
 * ({@code <actions>} / {@code <tool>} tags) and what the suggestion model proposed afterwards.
 *
 * <p>Extracted from {@code AssistantService} (Audit-TODO 7b5bbb4a). Pure functions; filtering
 * of mutating proposals is {@link AssistantMutationGate}'s job.
 */
public final class AssistantActionMerger {

    private AssistantActionMerger() {
    }

    /**
     * Merges pending-tools from two sources: tools the main model emitted via {@code <tool>}
     * tags (extraction.pendingTools()) and tools the suggestion model proposed via
     * {@code propose_*} actions (suggestion.pendingTools()). Both lists may be empty. Inline
     * tools are listed first; suggestion-model tools follow. No de-duplication — a duplicate
     * is unlikely (different code paths trigger them) and the user can reject either.
     */
    public static List<PendingTool> mergePendingTools(List<PendingTool> inline, List<PendingTool> suggested) {
        if ((inline == null || inline.isEmpty()) && (suggested == null || suggested.isEmpty())) {
            return List.of();
        }
        List<PendingTool> merged = new ArrayList<>();
        if (inline != null) merged.addAll(inline);
        if (suggested != null) merged.addAll(suggested);
        return merged;
    }

    /**
     * Merges inline actions (legacy path — main model may still emit {@code <actions>} tags) with
     * suggestion-model actions. Deduplicated by (type, id) — inline wins on conflict.
     */
    public static List<AssistantAction> mergeActions(List<AssistantAction> inline, List<AssistantAction> suggested) {
        if ((inline == null || inline.isEmpty()) && (suggested == null || suggested.isEmpty())) {
            return List.of();
        }
        Map<String, AssistantAction> byKey = new LinkedHashMap<>();
        if (inline != null) {
            for (AssistantAction a : inline) {
                if (a != null && a.type() != null) {
                    byKey.put(dedupeKey(a), a);
                }
            }
        }
        if (suggested != null) {
            for (AssistantAction a : suggested) {
                if (a != null && a.type() != null) {
                    byKey.putIfAbsent(dedupeKey(a), a);
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static String dedupeKey(AssistantAction a) {
        return a.type() + "|" + (a.id() == null ? "" : a.id());
    }
}
