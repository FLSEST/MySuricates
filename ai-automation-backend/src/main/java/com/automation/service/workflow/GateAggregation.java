package com.automation.service.workflow;

import com.automation.entity.GateStatus;
import com.automation.entity.StepResult;
import com.automation.executor.ExecutionContext;
import com.automation.service.runoutput.CommandExitDescription;
import com.automation.service.runoutput.GateStatusOutputParser;
import com.automation.service.runoutput.GateStatusOutputParser.ParsedGateStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The run's gate verdict plus every gate behind it, as {@link RunCompletionHandler} writes them into the run
 * metadata. {@code items} and {@code countsByStatus} become {@code gate_summary}, so trend tooling (e.g.
 * public-readiness chains) can diff runs without re-parsing step outputs.
 */
record GateAggregation(ParsedGateStatus verdict, List<Map<String, Object>> items,
                       Map<String, Integer> countsByStatus) {

    /**
     * Parses the gate_status markers of the given steps — highest severity wins, between equals see
     * {@link #closingGreenVerdict}. {@code null} when no step reported a gate.
     */
    static GateAggregation of(List<StepResult> markerSteps) {
        ParsedGateStatus verdict = null;
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Integer> countsByStatus = new HashMap<>();
        for (StepResult step : markerSteps) {
            Optional<ParsedGateStatus> parsed = GateStatusOutputParser.parse(step.getOutput());
            if (parsed.isEmpty()) {
                continue;
            }
            ParsedGateStatus gate = parsed.get();
            verdict = verdict == null ? gate : fold(verdict, gate);
            Map<String, Object> item = item(gate);
            item.put("step_index", step.getStepIndex());
            items.add(item);
            countsByStatus.merge(gate.status().name(), 1, Integer::sum);
        }
        return verdict == null ? null : new GateAggregation(verdict, items, countsByStatus);
    }

    /**
     * The stand-in for an ordered but missing verdict (TODO 63ecc124): YELLOW gate-missing, and in
     * gate_summary one item marked synthetic — no step delivered it, so it has no step_index.
     */
    static GateAggregation missing() {
        ParsedGateStatus verdict = new ParsedGateStatus(GateStatus.YELLOW,
                RunCompletionHandler.GATE_MISSING_CATEGORY, RunCompletionHandler.GATE_MISSING_DESCRIPTION);
        Map<String, Object> item = item(verdict);
        item.put("synthetic", true);
        return new GateAggregation(verdict, new ArrayList<>(List.of(item)),
                new HashMap<>(Map.of(verdict.status().name(), 1)));
    }

    /** gate_status/gate_category/gate_description carry the verdict, gate_summary all gates. */
    void writeTo(ExecutionContext context) {
        context.setRunMetadata("gate_status", verdict.status().name());
        if (verdict.category() != null) context.setRunMetadata("gate_category", verdict.category());
        if (verdict.description() != null) context.setRunMetadata("gate_description", verdict.description());

        Map<String, Object> gateSummary = new HashMap<>();
        gateSummary.put("highest", verdict.status().name());
        gateSummary.put("counts_by_status", countsByStatus);
        gateSummary.put("items", items);
        context.setRunMetadata("gate_summary", gateSummary);
    }

    /**
     * The verdict so far after one more gate: a worse one replaces all three fields, an equal one only as a
     * {@link #closingGreenVerdict}. Without a category of its own it keeps the one so far: GateStatusResolver
     * would put in "success" and so rename a track-mode TODO and the category: tag.
     */
    private static ParsedGateStatus fold(ParsedGateStatus soFar, ParsedGateStatus next) {
        if (next.status().ordinal() > soFar.status().ordinal()) {
            return next;
        }
        if (!closingGreenVerdict(next, soFar)) {
            return soFar;
        }
        String category = next.category() != null ? next.category() : soFar.category();
        return new ParsedGateStatus(soFar.status(), category, next.description());
    }

    /**
     * Whether an equal gate replaces the one so far as the run's {@code gate_description} (a worse one always
     * replaces all three fields). It depends on the status (TODO 2aa9ddd5).
     *
     * <p>RED and YELLOW keep the first: the cause — typically a {@code checked_command} whose category names what
     * failed — and exactly what an AutoTodo incident is keyed and titled by, so a closing verdict must not rename it.
     * GREEN has no cause, so the closing verdict speaks for the run — unless it says less than the one so far
     * ({@link #descriptionWeight}). Neither a {@code checked_command} before the verdict (the health check's first
     * step, whose {@code Command exit 0} stood in every report) nor one after it replaces the verdict, and a bare
     * later GREEN does not replace a described one.
     */
    private static boolean closingGreenVerdict(ParsedGateStatus next, ParsedGateStatus soFar) {
        return soFar.status() == GateStatus.GREEN && next.status() == GateStatus.GREEN
                && descriptionWeight(next.description()) >= descriptionWeight(soFar.description());
    }

    /** What a gate description says: words of its own (2), only a checked_command's exit code (1), nothing (0). */
    private static int descriptionWeight(String description) {
        if (description == null) {
            return 0;
        }
        return CommandExitDescription.isSynthetic(description) ? 1 : 2;
    }

    private static Map<String, Object> item(ParsedGateStatus gate) {
        Map<String, Object> item = new HashMap<>();
        item.put("status", gate.status().name());
        if (gate.category() != null) item.put("category", gate.category());
        if (gate.description() != null) item.put("description", gate.description());
        return item;
    }
}
