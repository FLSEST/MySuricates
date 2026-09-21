package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.executor.PlaceholderSyntax;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Regeln zur <b>Verdrahtung</b> einer Definition: Wird erzeugte Ausgabe auch gelesen, und zeigt
 * jeder Verweis auf etwas, das es gibt?
 *
 * <p>Anlass war PRD-07 (TODO 45509fcb): Ein Shell-Block lieferte 31 KB, die kein Folgeblock
 * referenzierte, und drei von vier Blöcken trugen einen Platzhalter, der außerhalb einer Chain
 * nie belegt wird und deshalb als Literal im Prompt ankam.
 */
final class WiringRules {

    private WiringRules() {}

    /** Nur innerhalb einer Chain belegt — in einem einzeln laufenden Workflow bleibt er Literal. */
    private static final Set<String> CHAIN_ONLY = Set.of(
            "previous_chain_output", "chain_run_id", "chain_step_output");

    static void apply(List<BlockDefinition> blocks, List<AnalyzerFinding> out) {
        Set<String> names = blockNames(blocks);
        List<String> texts = new ArrayList<>();
        for (BlockDefinition block : blocks) {
            texts.add(BlockText.joined(block));
        }

        unknownBlockReference(blocks, names, texts, out);
        chainOnlyPlaceholder(blocks, texts, out);
        unwiredOutput(blocks, texts, out);
    }

    /**
     * {@code block.<name>.output} auf einen Block, den die Definition nicht kennt. Der Resolver
     * lässt so einen Platzhalter unverändert stehen — der Prompt erhält die Zeichenfolge selbst
     * statt der Ausgabe, meist unbemerkt, weil der Lauf technisch durchläuft.
     */
    private static void unknownBlockReference(List<BlockDefinition> blocks, Set<String> names,
                                              List<String> texts, List<AnalyzerFinding> out) {
        for (int i = 0; i < blocks.size(); i++) {
            for (String key : PlaceholderSyntax.keysIn(texts.get(i))) {
                if (!key.startsWith("block.") || !key.endsWith(".output")) continue;
                String referenced = key.substring("block.".length(), key.length() - ".output".length());
                if (!names.contains(referenced)) {
                    out.add(AnalyzerFinding.of("unknown-block-reference", Severity.HIGH, i,
                            blocks.get(i).getName(),
                            "verweist auf {{ " + key + " }}, aber kein Block heißt \"" + referenced + "\""));
                }
            }
        }
    }

    /** Chain-Variable in einer Definition, die selbst keine Chain-Stufe ist. */
    private static void chainOnlyPlaceholder(List<BlockDefinition> blocks, List<String> texts,
                                             List<AnalyzerFinding> out) {
        for (int i = 0; i < blocks.size(); i++) {
            for (String key : new LinkedHashSet<>(PlaceholderSyntax.keysIn(texts.get(i)))) {
                if (CHAIN_ONLY.contains(key)) {
                    out.add(AnalyzerFinding.of("chain-only-placeholder", Severity.MEDIUM, i,
                            blocks.get(i).getName(),
                            "{{ " + key + " }} wird nur innerhalb einer Chain belegt; läuft der Workflow "
                                    + "einzeln, steht die Zeichenfolge selbst im Prompt"));
                }
            }
        }
    }

    /**
     * Ein Block erzeugt Ausgabe, die niemand liest. Geprüft wird jeder Block außer dem letzten:
     * Greift der unmittelbar folgende auf {@code previous_output} zu, oder nennt irgendein
     * späterer Block ihn über {@code block.<name>.output} beziehungsweise {@code step_N_output}?
     *
     * <p>Geprüft werden nur <b>KI-Blöcke</b>. Ein Shell-Block, dessen Ausgabe niemand liest, hat
     * oft genau dafür einen Grund — er schreibt eine Datei, die der nächste Block wieder einliest,
     * und kommuniziert über das Dateisystem statt über Platzhalter. Bei einem KI-Block ist eine
     * ungelesene Ausgabe dagegen bezahlter Text, den niemand bekommt. Ein erster Lauf über die
     * ausgelieferten Definitionen zeigte den Unterschied deutlich: ohne diese Einschränkung meldete
     * die Regel über hundert Treffer, von denen die meisten legitime Shell-Verkettungen waren —
     * und eine Regel mit so viel Rauschen wird abgeschaltet statt beachtet.
     *
     * <p>Wrapper-Blöcke (foreach, while, …) sind ebenfalls ausgenommen: Ihre inneren Blöcke reichen
     * Ausgaben untereinander weiter, was von außen wie eine tote Ausgabe aussieht.
     */
    private static void unwiredOutput(List<BlockDefinition> blocks, List<String> texts,
                                      List<AnalyzerFinding> out) {
        for (int i = 0; i < blocks.size() - 1; i++) {
            BlockDefinition block = blocks.get(i);
            if (isWrapper(block) || !isAiBlock(block)) continue;

            String next = texts.get(i + 1);
            if (next.contains("previous_output")) continue;

            String byName = block.getName() == null || block.getName().isBlank()
                    ? null : "block." + block.getName() + ".output";
            String byIndex = "step_" + i + "_output"; // Step-Indizes sind 0-basiert (BlockStepRunner)
            boolean readLater = false;
            for (int j = i + 1; j < blocks.size() && !readLater; j++) {
                String text = texts.get(j);
                readLater = text.contains(byIndex) || (byName != null && text.contains(byName));
            }
            if (!readLater) {
                out.add(AnalyzerFinding.of("unwired-output", Severity.MEDIUM, i, block.getName(),
                        "die Ausgabe dieses Blocks wird von keinem späteren Block gelesen "
                                + "(weder previous_output noch " + byIndex
                                + (byName == null ? "" : " noch " + byName) + ")"));
            }
        }
    }

    /** Nur hier kostet eine ungelesene Ausgabe Geld statt nur einen Dateizugriff. */
    private static boolean isAiBlock(BlockDefinition block) {
        String type = block.getType() == null ? "" : block.getType();
        return switch (type) {
            case "simple", "agentic", "skill-capability", "compress-ai" -> true;
            default -> false;
        };
    }

    /** Container-Blöcke führen ihre eigene Verdrahtung — von außen nicht beurteilbar. */
    private static boolean isWrapper(BlockDefinition block) {
        String type = block.getType() == null ? "" : block.getType();
        return switch (type) {
            case "foreach", "while", "conditional", "switch", "retry", "sub-workflow" -> true;
            default -> false;
        };
    }

    private static Set<String> blockNames(List<BlockDefinition> blocks) {
        Set<String> names = new LinkedHashSet<>();
        for (BlockDefinition block : blocks) {
            if (block.getName() != null && !block.getName().isBlank()) names.add(block.getName());
        }
        return names;
    }
}
