package com.automation.service.assistant;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse.AssistantAction;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gate for mutating proposals in the assistant chat: only when the user's message carries an
 * explicit mutation intent (verb + target, or a run/workflow verb on a run/workflow page) may
 * the response keep mutating actions such as {@code run_workflow}.
 *
 * <p>Extracted from {@code AssistantService} (Audit-TODO 7b5bbb4a). Covers the action side;
 * the tool side ({@code <tool>} proposals) is gated by {@code AssistantToolExecutor}.
 */
public final class AssistantMutationGate {

    private AssistantMutationGate() {
    }

    static final Set<String> MUTATING_ACTION_TYPES = Set.of("run_workflow");
    private static final Pattern EXPLICIT_MUTATION_VERB =
            Pattern.compile("(?iu)\\b(?:leg(?:e|en|st)?|erstell(?:e|en|st)?|schreib(?:e|en|st)?|mach(?:e|en|st)?|start(?:e|en|est)?|ausf(?:u|ü)hr(?:e|en|st)?|aktualisier(?:e|en|st)?|(?:ver)?änder(?:e|n|st)?|l(?:o|ö)sch(?:e|en|st)?|entfern(?:e|en|st)?|stopp(?:e|en)?|brich(?:\\s+\\w+){0,2}\\s+ab|markier(?:e|en|st)?)\\b");
    private static final Pattern EXPLICIT_MUTATION_TARGET =
            Pattern.compile("(?iu)\\b(?:todo|aufgabe|workflow|chain|run|ausf(?:u|ü)hrung|wish|wunsch|status)\\b");
    private static final Pattern CONTEXTUAL_RUN_VERB =
            Pattern.compile("(?iu)\\b(?:start(?:e|en|est)?|ausf(?:u|ü)hr(?:e|en|st)?|erneut(?:\\s+ausf(?:u|ü)hren)?|nochmal(?:\\s+ausf(?:u|ü)hren)?|nochmals(?:\\s+ausf(?:u|ü)hren)?|wiederholen|retry|rerun|abbrechen|stopp(?:e|en)?)\\b");

    public static List<AssistantAction> filterActions(List<AssistantAction> actions, boolean allowMutatingProposals) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        return actions.stream()
                .filter(action -> action != null)
                .filter(action -> allowMutatingProposals || !isMutatingActionType(action.type()))
                .toList();
    }

    public static boolean isMutatingActionType(String type) {
        return type != null && MUTATING_ACTION_TYPES.contains(type);
    }

    public static boolean hasExplicitMutationIntent(AssistantChatRequest req) {
        String message = req != null ? req.message() : null;
        if (message == null || message.isBlank()) {
            return false;
        }
        if (EXPLICIT_MUTATION_VERB.matcher(message).find()
                && EXPLICIT_MUTATION_TARGET.matcher(message).find()) {
            return true;
        }

        String page = req.context() != null ? req.context().page() : null;
        if (page == null) {
            return false;
        }
        boolean contextualRunPage = "run".equals(page) || "runs".equals(page)
                || "workflow".equals(page) || "workflows".equals(page);
        return contextualRunPage && CONTEXTUAL_RUN_VERB.matcher(message).find();
    }
}
