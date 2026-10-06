package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.executor.ApprovalRejection;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Rules for {@code approval} blocks, top-level and nested (foreach, while, conditional, …).
 *
 * <p>{@code on-no-without-reject-option}: a block sets {@code on_no: fail} or {@code skip}, but
 * none of its {@code options} counts as a rejection - neither via {@code reject_options} nor via the
 * default "No"/"Nein". {@code /approve} only accepts offered options, so the rejection can
 * never occur and {@code on_no} never takes effect (TODO 41aeeb09, split off from 50c7b5d5). The rule
 * uses the same {@link ApprovalRejection} as the block, so that check and execution do not
 * drift apart.
 */
final class ApprovalRules {

    private ApprovalRules() {}

    private static final List<String> DEFAULT_OPTIONS = List.of("Yes", "Yes always", "No");

    /** Protection against pathologically deep configs, as in {@link BlockText}. */
    private static final int MAX_DEPTH = 12;

    static void apply(List<BlockDefinition> blocks, List<AnalyzerFinding> out) {
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if ("approval".equals(block.getType())) {
                check(block.getConfig(), i, block.getName(), out);
            } else {
                nested(block.getConfig(), i, block.getName(), out, 0);
            }
        }
    }

    /** Nested blocks carry their type and config as a map; the finding is reported on the outer block. */
    private static void nested(Object value, int index, String outerName, List<AnalyzerFinding> out, int depth) {
        if (value == null || depth > MAX_DEPTH) return;
        if (value instanceof Map<?, ?> map) {
            if ("approval".equals(map.get("type")) && map.get("config") instanceof Map<?, ?> config) {
                Object name = map.get("name");
                check(config, index, name instanceof String n ? outerName + " › " + n : outerName, out);
            }
            map.values().forEach(v -> nested(v, index, outerName, out, depth + 1));
        } else if (value instanceof Collection<?> items) {
            items.forEach(item -> nested(item, index, outerName, out, depth + 1));
        }
    }

    private static void check(Map<?, ?> config, int index, String name, List<AnalyzerFinding> out) {
        if (config == null) return;
        Object onNo = config.get("on_no");
        if (!"fail".equals(onNo) && !"skip".equals(onNo)) return;

        List<String> rejectAnswers = ApprovalRejection.rejectAnswers(config.get("reject_options"));
        if (config.get("auto_answer") instanceof String auto && ApprovalRejection.isRejection(auto, rejectAnswers)) {
            return; // the automatic answer itself rejects - on_no takes effect
        }
        List<String> options = config.get("options") instanceof List<?> list
                ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : DEFAULT_OPTIONS;
        if (!ApprovalRejection.rejectingOptions(options, rejectAnswers).isEmpty()) return;

        out.add(AnalyzerFinding.of("on-no-without-reject-option", Severity.HIGH, index, name,
                "setzt on_no: " + onNo + ", aber keine der Optionen " + options
                        + " gilt als Ablehnung (Ablehnungen: " + rejectAnswers + ") — on_no greift nie; "
                        + "reject_options setzen",
                "sets on_no: " + onNo + ", but none of the options " + options
                        + " counts as a rejection (rejections: " + rejectAnswers + ") — on_no never applies; "
                        + "set reject_options"));
    }
}
