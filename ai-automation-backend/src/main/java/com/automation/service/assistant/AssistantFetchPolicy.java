package com.automation.service.assistant;

import com.automation.service.assistant.AssistantFetchService.FetchRequest;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which {@code <fetch>} requests the chat loop may resolve automatically and when a
 * model response already counts as a substantive answer (→ stop fetching).
 *
 * <p>Extracted from {@code AssistantService} (Audit-TODO 7b5bbb4a). Pure functions, no state;
 * the round/fetch limits and the loop itself stay in the service.
 */
public final class AssistantFetchPolicy {

    private AssistantFetchPolicy() {
    }

    static final Set<String> AUTO_FETCH_TYPES = Set.of(
            "run_detail",
            "step_output",
            "wish_detail",
            "workflow_runs",
            "workflow_detail",
            "workflow-inputs",
            "find_workflow",
            "project_files",
            "project_file",
            "docs",
            "skill",
            "integration",
            "chain",
            "block-spec"
    );

    /** Threshold at which text outside {@code <fetch>} tags counts as a real answer, not a filler
     *  announcement. 200 chars is comfortably longer than typical "Ich schaue mir X an…" stalls
     *  but short enough that a one-sentence factual answer still counts. */
    static final int ANSWER_TEXT_MIN_CHARS = 200;

    /** Regex for common announcement openings — catches the stall responses that cost users a
     *  round-trip without delivering content. Anchored at start, case-insensitive. */
    private static final Pattern ANNOUNCEMENT_PATTERN = Pattern.compile(
            "(?is)^\\s*(ich\\s+(schau|hole|lade|pr(ü|u)f|prufe|analysier|check|verifizier)|lade\\s+|einen\\s+moment|moment\\b|kurz\\s+nachladen|sekunde).{0,200}",
            Pattern.CASE_INSENSITIVE);

    public static boolean isAutoFetchAllowed(FetchRequest req) {
        return req != null && AUTO_FETCH_TYPES.contains(req.type());
    }

    /** Dedup key across fetch rounds: {@code type|id}, nulls rendered as empty. */
    public static String fetchKey(FetchRequest req) {
        return (req.type() == null ? "" : req.type()) + "|" + (req.id() == null ? "" : req.id());
    }

    /**
     * A response counts as a "substantive answer" (→ stop the fetch loop) when the non-fetch
     * text is long enough to be a real answer OR carries an {@code <actions>} block. Short
     * announcement-style filler ("Ich schaue mir den Run an…") is explicitly NOT substantive,
     * even though it's non-blank — otherwise a stripped {@code <fetch>} would leave the user
     * with nothing but the announcement.
     */
    public static boolean hasSubstantiveAnswer(String rawOutput, String nonFetchText) {
        if (nonFetchText == null || nonFetchText.isBlank()) {
            return false;
        }
        if (rawOutput != null && rawOutput.contains("<actions>")) {
            return true;
        }
        String trimmed = nonFetchText.trim();
        if (trimmed.length() >= ANSWER_TEXT_MIN_CHARS) {
            return true;
        }
        // Short text — treat as announcement filler and continue the fetch loop.
        return !ANNOUNCEMENT_PATTERN.matcher(trimmed).find() && trimmed.length() >= 40;
    }
}
