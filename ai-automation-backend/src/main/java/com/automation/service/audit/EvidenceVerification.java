package com.automation.service.audit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prüft, ob ein Audit-Finding sich auf eine Messung <b>dieses</b> Laufs beruft.
 *
 * <p>Der Audit-Prompt bindet jedes Finding an eine Belegzeile („Jedes Finding nennt in
 * {@code evidence} die FAKT- oder TREFFER-Zeile, auf der es beruht"). Geprüft hat das niemand:
 * ein Finding mit erfundenem oder aus dem letzten Report recyceltem Beleg wurde unverändert zu
 * einem TODO — bei {@code severity: HIGH} zu einem, das oben in der Liste steht (TODO a94b56a0).
 *
 * <p>Die Prüfung ist deterministisch und arbeitet ohne KI: Aus dem Faktenblatt des Runs werden
 * die Namen aller {@code FAKT <name>:}- und {@code TREFFER <name>:}-Zeilen gesammelt; eine
 * {@code evidence}, die keinen davon nennt, gilt als unbelegt.
 *
 * <p><b>Was sie leistet und was nicht.</b> Sie fängt Belege, die es in diesem Lauf nicht gibt —
 * erfundene Namen und solche aus einem früheren Report. Sie kann <i>nicht</i> beurteilen, ob ein
 * tatsächlich vorhandener Messwert die Schlussfolgerung trägt: Wer {@code FAKT
 * non_main_runtime_timeout_lines} zitiert und daraus eine unbelegte Aussage über eine ganz andere
 * Stelle ableitet, kommt weiterhin durch. Das ist die Grenze einer mechanischen Prüfung; sie
 * verschiebt die Messregel aber vom Prompt ins Backend, wo sie sich nicht wegformulieren lässt.
 */
final class EvidenceVerification {

    private EvidenceVerification() {}

    /** Kopfzeile, an der ein Messblock-Output als Faktenblatt erkannt wird. */
    private static final String FACT_SHEET_MARKER = "## FAKTENBLATT";

    /** {@code FAKT <name>: <wert>} und {@code TREFFER <name>: <datei>:<zeile>} — nur der Name zählt. */
    private static final Pattern FACT_NAME = Pattern.compile(
            "^\\s*(?:FAKT|TREFFER)\\s+([A-Za-z0-9_.:-]+)\\s*:", Pattern.MULTILINE);

    /** Kürzer als das hier ist kein Name, nach dem sich in einer Evidence suchen lässt. */
    private static final int MIN_NAME_LENGTH = 4;

    /**
     * Sammelt die Namen aller Messwerte aus den Ausgaben der Faktenblatt-Blöcke eines Runs.
     *
     * @param stepOutputs Ausgaben aller Schritte, in beliebiger Reihenfolge
     * @return gefundene Namen; <b>leer</b>, wenn der Run kein Faktenblatt hat — dann ist nichts
     *         zu prüfen, und {@link #isSupported} lässt jedes Finding durch
     */
    static Set<String> factNames(List<String> stepOutputs) {
        Set<String> names = new LinkedHashSet<>();
        if (stepOutputs == null) return names;
        for (String output : stepOutputs) {
            if (output == null || !output.contains(FACT_SHEET_MARKER)) continue;
            Matcher matcher = FACT_NAME.matcher(output);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (name.length() >= MIN_NAME_LENGTH) names.add(name);
            }
        }
        return names;
    }

    /**
     * @return {@code true}, wenn die Evidence mindestens einen Messwert dieses Laufs nennt — oder
     *         wenn der Run gar kein Faktenblatt hat (nichts zu prüfen, kein Verdacht)
     */
    static boolean isSupported(String evidence, Set<String> factNames) {
        if (factNames.isEmpty()) return true;
        if (evidence == null || evidence.isBlank()) return false;
        return factNames.stream().anyMatch(evidence::contains);
    }
}
