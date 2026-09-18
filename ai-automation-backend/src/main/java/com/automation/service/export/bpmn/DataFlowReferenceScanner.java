package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;
import com.automation.executor.PlaceholderSyntax;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leitet deterministisch aus den Block-Configs ab, welche Block-Ausgabe in welchen Block fließt —
 * ohne Run, ohne KI. Grundlage des Datenfluss-Exports (Datenobjekte/-assoziationen).
 *
 * <p>Erkannte Formen (dieselbe Platzhalter-Schreibweise wie zur Laufzeit,
 * {@link PlaceholderSyntax#PLACEHOLDER}):
 * <ul>
 *   <li>{@code {{ block.&lt;name&gt;.output }}} — Quelle ist der Block mit diesem Namen</li>
 *   <li>{@code {{ previous_output }}} — Quelle ist der unmittelbar vorhergehende Block</li>
 *   <li>{@code {{ step_&lt;N&gt;_output }}} — Quelle ist der Block mit Index N. <b>0-basiert</b>, wie
 *       {@code StepIndexCounter} die Step-Indizes vergibt und {@code BlockStepRunner} die Variable
 *       {@code step_&lt;stepIndex&gt;_output} setzt. N ist allerdings ein <em>Laufzeit</em>-Step-Index:
 *       Wrapper-Blöcke (foreach, while, retry) ziehen je Item/Iteration einen weiteren Index, danach
 *       stimmt die Zuordnung Step-Index → Blocklisten-Index nicht mehr. Steht einer dieser Typen
 *       in {@code blocks[0..N]}, wird die Referenz verworfen — lieber keine Kante als eine falsche.</li>
 *   <li>{@code source} der Komprimierungs-Blöcke ({@code compress}, {@code compress-ai}):
 *       Blockname oder {@code "previous"} (siehe {@code CompressionSources})</li>
 * </ul>
 *
 * <p>Ebenen: Gescannt werden alle String-Werte einer Block-Config rekursiv. Positionsbezogene
 * Platzhalter ({@code previous_output}, {@code step_N_output}) gelten nur für die <b>oberste
 * Ebene</b> — die unmittelbaren Werte der Config-Map. Weiter unten (Switch-Cases, verschachtelte
 * Blocklisten) meinen sie zur Laufzeit die innere Ebene, nicht die Top-Level-Blockliste; dort wird
 * deshalb ausschließlich {@code block.&lt;name&gt;.output} ausgewertet, dessen Namensraum flach ist.
 * Eine so gefundene Referenz zählt für den Top-Level-Eltern-Block.
 *
 * <p>Weitere Grenzen (bewusst): Quellen müssen im Workflow <em>vor</em> dem konsumierenden Block
 * stehen; mehrdeutige (mehrfach vergebene) Blocknamen werden übersprungen, weil zur Laufzeit nicht
 * bestimmbar ist, welcher Block gemeint ist.
 */
final class DataFlowReferenceScanner {

    /** Die Ausgabe von {@code sourceIndex} fließt in {@code targetIndex} (Indizes der Blockliste). */
    record Reference(int sourceIndex, int targetIndex) {
    }

    private static final Pattern STEP_OUTPUT = Pattern.compile("step_(\\d{1,6})_output");
    private static final String BLOCK_PREFIX = "block.";
    private static final String OUTPUT_SUFFIX = ".output";
    /** Block-Typen, die zur Laufzeit mehr als einen Step-Index verbrauchen. */
    private static final Set<String> WRAPPER_TYPES = Set.of("foreach", "while", "retry");

    /** String-Werte einer Config, getrennt nach direktem Config-Wert und tieferer Verschachtelung. */
    private record Strings(List<String> direct, List<String> nested) {
    }

    private DataFlowReferenceScanner() {
    }

    static List<Reference> scan(List<BlockDefinition> blocks) {
        if (blocks == null || blocks.size() < 2) {
            return List.of();
        }
        Map<String, Integer> byName = uniqueNameIndex(blocks);
        Set<Reference> found = new LinkedHashSet<>();
        for (int target = 0; target < blocks.size(); target++) {
            BlockDefinition block = blocks.get(target);
            Strings texts = stringsOf(block.getConfig());
            for (String text : texts.direct()) {
                for (String key : PlaceholderSyntax.keysIn(text)) {
                    add(found, sourceOf(key, target, byName, blocks), target);
                }
            }
            for (String text : texts.nested()) {
                for (String key : PlaceholderSyntax.keysIn(text)) {
                    add(found, namedSource(key, byName), target);
                }
            }
            add(found, compressionSource(block, target, byName), target);
        }
        List<Reference> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparingInt(Reference::targetIndex).thenComparingInt(Reference::sourceIndex));
        return List.copyOf(sorted);
    }

    private static void add(Set<Reference> found, int source, int target) {
        // Nur Rückwärts-Referenzen: eine Quelle nach dem Ziel (oder der Block selbst) liefert zur
        // Laufzeit nichts, der Platzhalter bliebe literal stehen.
        if (source >= 0 && source < target) {
            found.add(new Reference(source, target));
        }
    }

    /**
     * Quelle eines Platzhalters auf der obersten Ebene (direkter Config-Wert des Blocks).
     * -1, wenn der Schlüssel keine Block-Ausgabe bezeichnet oder die Quelle nicht eindeutig ist.
     */
    private static int sourceOf(String key, int target, Map<String, Integer> byName, List<BlockDefinition> blocks) {
        if ("previous_output".equals(key)) {
            return target - 1;
        }
        int named = namedSource(key, byName);
        if (named >= 0) {
            return named;
        }
        Matcher step = STEP_OUTPUT.matcher(key);
        if (!step.matches()) {
            return -1;
        }
        int index = Integer.parseInt(step.group(1));
        // step_N ist ein LAUFZEIT-Step-Index, kein Blocklisten-Index: Wrapper-Blöcke ziehen je Item
        // bzw. Iteration einen weiteren Index (StepIndexCounter), danach zeigt N auf einen anderen
        // Schritt als auf Block N. Statisch nicht auflösbar ⇒ lieber keine Kante als eine falsche.
        return hasWrapperUpTo(blocks, index) ? -1 : index;
    }

    /** Nur {@code block.<name>.output} — der einzige Platzhalter mit ebenen-unabhängigem Namensraum. */
    private static int namedSource(String key, Map<String, Integer> byName) {
        if (key.startsWith(BLOCK_PREFIX) && key.endsWith(OUTPUT_SUFFIX)
                && key.length() > BLOCK_PREFIX.length() + OUTPUT_SUFFIX.length()) {
            String name = key.substring(BLOCK_PREFIX.length(), key.length() - OUTPUT_SUFFIX.length());
            return byName.getOrDefault(name, -1);
        }
        return -1;
    }

    private static boolean hasWrapperUpTo(List<BlockDefinition> blocks, int index) {
        for (int i = 0; i <= index && i < blocks.size(); i++) {
            if (WRAPPER_TYPES.contains(blocks.get(i).getType())) {
                return true;
            }
        }
        return false;
    }

    /** {@code source} von compress/compress-ai: Blockname oder {@code "previous"}. */
    private static int compressionSource(BlockDefinition block, int target, Map<String, Integer> byName) {
        String type = block.getType() == null ? "" : block.getType();
        if (!"compress".equals(type) && !"compress-ai".equals(type)) {
            return -1;
        }
        Object raw = block.getConfig() == null ? null : block.getConfig().get("source");
        if (!(raw instanceof String source) || source.isBlank()) {
            return -1;
        }
        String value = source.strip();
        return "previous".equals(value) ? target - 1 : byName.getOrDefault(value, -1);
    }

    /** Blockname → Index; mehrfach vergebene Namen fallen heraus (zur Laufzeit mehrdeutig). */
    private static Map<String, Integer> uniqueNameIndex(List<BlockDefinition> blocks) {
        Map<String, Integer> byName = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (int i = 0; i < blocks.size(); i++) {
            String name = blocks.get(i).getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            if (byName.putIfAbsent(name.strip(), i) != null) {
                ambiguous.add(name.strip());
            }
        }
        ambiguous.forEach(byName::remove);
        return byName;
    }

    /**
     * Alle String-Werte einer Config, rekursiv über Maps und Listen. Getrennt wird nach Ebene:
     * {@code direct} sind die unmittelbaren Werte der Config-Map (die oberste Ebene des Blocks),
     * {@code nested} alles darunter — Switch-Cases, Conditional-Zweiglisten, verschachtelte
     * Blocklisten. Nur auf der obersten Ebene meinen positionsbezogene Platzhalter die
     * Top-Level-Blockliste.
     */
    private static Strings stringsOf(Object config) {
        List<String> direct = new ArrayList<>();
        List<String> nested = new ArrayList<>();
        collect(config, direct, nested, 0);
        return new Strings(direct, nested);
    }

    private static void collect(Object value, List<String> direct, List<String> nested, int depth) {
        if (value == null || depth > 20) {
            return;
        }
        if (value instanceof String s) {
            if (s.indexOf('{') >= 0) {
                (depth <= 1 ? direct : nested).add(s);
            }
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collect(v, direct, nested, depth + 1));
        } else if (value instanceof Iterable<?> list) {
            list.forEach(v -> collect(v, direct, nested, depth + 1));
        }
    }
}
