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
 *
 * <p>Zweige (7557b928): Eine so gefundene Referenz zählt für den <em>Zweig-Block</em>, in dem sie
 * steht, nicht für den Switch — der wird zu einem Gateway, und BPMN erlaubt an Gateways keine
 * Datenassoziationen. Endpunkt ist deshalb der Task des Zweig-Blocks ({@link Endpoint#branchPath()},
 * Schreibweise in {@link BpmnBranchBlocks}). Umgekehrt ist ein benannter Case-Block auch eine
 * <em>Quelle</em>: seine Ausgabe ist zur Laufzeit als {@code block.&lt;name&gt;.output} ansprechbar
 * (12a3b244).
 *
 * <p>Weitere Grenzen (bewusst): Quellen müssen im Workflow <em>vor</em> dem konsumierenden
 * Top-Level-Block stehen — innerhalb desselben Switch entsteht also keine Kante, denn zur Laufzeit
 * läuft nur ein Zweig. Mehrdeutige (mehrfach vergebene) Blocknamen werden übersprungen, weil zur
 * Laufzeit nicht bestimmbar ist, welcher Block gemeint ist.
 */
final class DataFlowReferenceScanner {

    /**
     * Endpunkt einer Referenz: der Top-Level-Block {@code blockIndex} oder, für einen Block in
     * einem seiner Zweige, dieser Zweig-Block. {@code branchPath} ist leer für den Block selbst und
     * sonst der Id-Pfad relativ zum Top-Level-Block (z. B. {@code _c0_1}, {@code _default_0}).
     */
    record Endpoint(int blockIndex, String branchPath) {

        static Endpoint topLevel(int blockIndex) {
            return new Endpoint(blockIndex, "");
        }

        /** Id-Basis des zugehörigen Knotens; {@code rootPrefix} ist das Präfix der Top-Level-Kette. */
        String idBase(String rootPrefix) {
            return BpmnBranchBlocks.member(rootPrefix, blockIndex) + branchPath;
        }
    }

    /** Die Ausgabe von {@code source} fließt in {@code target}. */
    record Reference(Endpoint source, Endpoint target) {

        Reference(int sourceIndex, int targetIndex) {
            this(Endpoint.topLevel(sourceIndex), Endpoint.topLevel(targetIndex));
        }

        int sourceIndex() {
            return source.blockIndex();
        }

        int targetIndex() {
            return target.blockIndex();
        }
    }

    private static final Pattern STEP_OUTPUT = Pattern.compile("step_(\\d{1,6})_output");
    private static final String BLOCK_PREFIX = "block.";
    private static final String OUTPUT_SUFFIX = ".output";
    /** Block-Typen, die zur Laufzeit mehr als einen Step-Index verbrauchen. */
    private static final Set<String> WRAPPER_TYPES = Set.of("foreach", "while", "retry");

    private DataFlowReferenceScanner() {
    }

    static List<Reference> scan(List<BlockDefinition> blocks) {
        if (blocks == null || blocks.size() < 2) {
            return List.of();
        }
        Map<String, Endpoint> byName = uniqueNameIndex(blocks);
        Set<Reference> found = new LinkedHashSet<>();
        for (int target = 0; target < blocks.size(); target++) {
            BlockDefinition block = blocks.get(target);
            Endpoint self = Endpoint.topLevel(target);
            DataFlowConfigStrings texts = DataFlowConfigStrings.of(block.getConfig());
            for (String text : texts.direct()) {
                for (String key : PlaceholderSyntax.keysIn(text)) {
                    add(found, sourceOf(key, target, byName, blocks), self);
                }
            }
            List<BpmnBranchBlocks.Branch> branches = BpmnBranchBlocks.of(block);
            if (branches.isEmpty()) {
                // Verschachtelte Werte ohne eigene Blöcke (z. B. integration-params) zählen für den Block.
                for (String text : texts.nested()) {
                    for (String key : PlaceholderSyntax.keysIn(text)) {
                        add(found, namedSource(key, byName), self);
                    }
                }
            } else {
                scanBranches(found, branches, target, byName);
            }
            add(found, compressionSource(block, target, byName), self);
        }
        List<Reference> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparingInt(Reference::targetIndex)
                .thenComparing(r -> r.target().branchPath())
                .thenComparingInt(Reference::sourceIndex)
                .thenComparing(r -> r.source().branchPath()));
        return List.copyOf(sorted);
    }

    /**
     * Zweig-Blöcke eines Switch: jede Referenz endet am Task des Zweig-Blocks. Innerhalb eines
     * Zweigs gilt durchgehend die Ebenen-Semantik — nur {@code block.<name>.output} und der
     * Blockname als Komprimierungs-Quelle, nie positionsbezogene Platzhalter.
     */
    private static void scanBranches(Set<Reference> found, List<BpmnBranchBlocks.Branch> branches,
                                     int target, Map<String, Endpoint> byName) {
        for (BpmnBranchBlocks.Branch branch : branches) {
            Endpoint self = new Endpoint(target, branch.path());
            DataFlowConfigStrings texts = DataFlowConfigStrings.of(branch.block().getConfig());
            List<String> own = new ArrayList<>(texts.direct());
            // Ist der Zweig-Block selbst eine Verzweigung, stehen seine tieferen Werte schon als
            // eigene Zweig-Blöcke in dieser Liste — sonst zählte dieselbe Referenz zweimal.
            if (BpmnBranchBlocks.of(branch.block()).isEmpty()) {
                own.addAll(texts.nested());
            }
            for (String text : own) {
                for (String key : PlaceholderSyntax.keysIn(text)) {
                    add(found, namedSource(key, byName), self);
                }
            }
            add(found, namedCompressionSource(branch.block(), byName), self);
        }
    }

    private static void add(Set<Reference> found, Endpoint source, Endpoint target) {
        // Nur Rückwärts-Referenzen: eine Quelle nach dem Ziel (oder im selben Top-Level-Block)
        // liefert zur Laufzeit nichts, der Platzhalter bliebe literal stehen.
        if (source != null && source.blockIndex() >= 0 && source.blockIndex() < target.blockIndex()) {
            found.add(new Reference(source, target));
        }
    }

    /**
     * Quelle eines Platzhalters auf der obersten Ebene (direkter Config-Wert des Blocks).
     * {@code null}, wenn der Schlüssel keine Block-Ausgabe bezeichnet oder die Quelle nicht eindeutig ist.
     */
    private static Endpoint sourceOf(String key, int target, Map<String, Endpoint> byName, List<BlockDefinition> blocks) {
        if ("previous_output".equals(key)) {
            return Endpoint.topLevel(target - 1);
        }
        Endpoint named = namedSource(key, byName);
        if (named != null) {
            return named;
        }
        Matcher step = STEP_OUTPUT.matcher(key);
        if (!step.matches()) {
            return null;
        }
        int index = Integer.parseInt(step.group(1));
        // step_N ist ein LAUFZEIT-Step-Index, kein Blocklisten-Index: Wrapper-Blöcke ziehen je Item
        // bzw. Iteration einen weiteren Index (StepIndexCounter), danach zeigt N auf einen anderen
        // Schritt als auf Block N. Statisch nicht auflösbar ⇒ lieber keine Kante als eine falsche.
        return hasWrapperUpTo(blocks, index) ? null : Endpoint.topLevel(index);
    }

    /** Nur {@code block.<name>.output} — der einzige Platzhalter mit ebenen-unabhängigem Namensraum. */
    private static Endpoint namedSource(String key, Map<String, Endpoint> byName) {
        if (key.startsWith(BLOCK_PREFIX) && key.endsWith(OUTPUT_SUFFIX)
                && key.length() > BLOCK_PREFIX.length() + OUTPUT_SUFFIX.length()) {
            return byName.get(key.substring(BLOCK_PREFIX.length(), key.length() - OUTPUT_SUFFIX.length()));
        }
        return null;
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
    private static Endpoint compressionSource(BlockDefinition block, int target, Map<String, Endpoint> byName) {
        String value = compressionSourceValue(block);
        if (value == null) {
            return null;
        }
        return "previous".equals(value) ? Endpoint.topLevel(target - 1) : byName.get(value);
    }

    /** Wie {@link #compressionSource}, aber ohne {@code "previous"} — das meint in einem Zweig die innere Ebene. */
    private static Endpoint namedCompressionSource(BlockDefinition block, Map<String, Endpoint> byName) {
        String value = compressionSourceValue(block);
        return value == null || "previous".equals(value) ? null : byName.get(value);
    }

    private static String compressionSourceValue(BlockDefinition block) {
        String type = block.getType() == null ? "" : block.getType();
        if (!"compress".equals(type) && !"compress-ai".equals(type)) {
            return null;
        }
        Object raw = block.getConfig() == null ? null : block.getConfig().get("source");
        return raw instanceof String source && !source.isBlank() ? source.strip() : null;
    }

    /**
     * Blockname → Endpunkt, über Top-Level-Blöcke <em>und</em> deren Zweig-Blöcke: der Namensraum
     * von {@code block.<name>.output} ist zur Laufzeit flach (12a3b244). Mehrfach vergebene Namen
     * fallen heraus (zur Laufzeit mehrdeutig).
     */
    private static Map<String, Endpoint> uniqueNameIndex(List<BlockDefinition> blocks) {
        Map<String, Endpoint> byName = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (int i = 0; i < blocks.size(); i++) {
            put(byName, ambiguous, blocks.get(i).getName(), Endpoint.topLevel(i));
            for (BpmnBranchBlocks.Branch branch : BpmnBranchBlocks.of(blocks.get(i))) {
                put(byName, ambiguous, branch.block().getName(), new Endpoint(i, branch.path()));
            }
        }
        ambiguous.forEach(byName::remove);
        return byName;
    }

    private static void put(Map<String, Endpoint> byName, Set<String> ambiguous, String name, Endpoint endpoint) {
        if (name == null || name.isBlank()) {
            return;
        }
        if (byName.putIfAbsent(name.strip(), endpoint) != null) {
            ambiguous.add(name.strip());
        }
    }
}
