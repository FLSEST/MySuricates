package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.executor.NestedBlocks;
import com.automation.executor.blocks.StepActors;
import com.automation.modules.ModuleCatalog;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code actor-module-disabled} (TODO dca0c03e): an {@code approval} or {@code human-input} block casts an AI
 * actor ({@code actor: ai} or {@code shadow}), but the module {@code ai-actors} is off on this instance — the
 * step would fail at run time, never silently ask a person. A literal value is {@code HIGH}; a placeholder
 * ({@code actor: "{{ actor_po }}"}) may resolve to an AI actor and is {@code MEDIUM} for review, because the
 * run variable decides. Nothing is reported while the module is on.
 */
final class ActorRules {

    private ActorRules() {}

    static final String RULE = "actor-module-disabled";

    private static final Set<String> CAST_TYPES = Set.of("approval", "human-input");

    static void apply(List<BlockDefinition> blocks, boolean moduleEnabled, List<AnalyzerFinding> out) {
        if (moduleEnabled) return;
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null) continue;
            int index = i;
            String outer = block.getName();
            check(block.getType(), block.getConfig(), index, outer, out);
            NestedBlocks.visit(block.getConfig(), nested -> {
                boolean named = nested.name() != null && !nested.name().isBlank();
                check(nested.type(), nested.config(), index, outer + (named ? " › " + nested.name() : ""), out);
            });
        }
    }

    private static void check(String type, Map<String, Object> config, int index, String name, List<AnalyzerFinding> out) {
        if (type == null || !CAST_TYPES.contains(type) || config == null) return;
        Object raw = config.get(StepActors.ACTOR_KEY);
        if (!(raw instanceof String value) || value.isBlank()) return;
        String property = ModuleCatalog.AI_ACTORS.property();
        if (StepActors.asksForAi(value)) {
            out.add(AnalyzerFinding.of(RULE, Severity.HIGH, index, name,
                    "setzt actor: " + value.trim() + ", aber das Modul ai-actors ist aus — der Schritt scheitert "
                            + "beim Lauf statt still einen Menschen zu fragen; " + property + "=true setzen oder "
                            + "actor: human",
                    "sets actor: " + value.trim() + ", but the module ai-actors is off — the step fails at run "
                            + "time instead of silently asking a person; set " + property + "=true or actor: human"));
        } else if (value.contains("{{")) {
            out.add(AnalyzerFinding.of(RULE, Severity.MEDIUM, index, name,
                    "setzt actor per Platzhalter (" + value.trim() + "), aber das Modul ai-actors ist aus — löst "
                            + "die Variable zu ai oder shadow auf, scheitert der Schritt beim Lauf; prüfen",
                    "sets actor via placeholder (" + value.trim() + "), but the module ai-actors is off — if the "
                            + "variable resolves to ai or shadow the step fails at run time; check"));
        }
    }
}
