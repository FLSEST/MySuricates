package com.automation.service.audit;

import java.util.regex.Pattern;

/**
 * Normalisiert das modellgenerierte {@code subject} eines Audit-Findings, damit der Fingerprint
 * nicht bei jedem Run driftet (Audit-TODO 0e19648d). Beobachtete Drift: {@code ContainerShellService}
 * ↔ {@code ContainerShellService.execute}, {@code application.yml} ↔ {@code application.yml:key-store-password}.
 *
 * <p>Reine Funktion, bewusst konservativ:
 * <ul>
 *   <li>steht vor dem ersten {@code :} ein klein geschriebenes Art-Wort ({@code package},
 *       {@code keyword}, {@code safe-inventory}), ist {@code <art>:<wert>} die Identitaet und bleibt
 *       ganz — sonst fielen {@code package:a} und {@code package:b} zusammen und die Ingestion verwarf
 *       den zweiten Befund (TODO 94fda6ef),</li>
 *   <li>sonst (Datei, Klasse, Pfad vor dem {@code :}) faellt alles ab dem ersten {@code :} weg
 *       (Schluessel, Zeilennummer),</li>
 *   <li>ein Suffix hinter dem letzten {@code .} faellt weg, wenn es klein beginnt (Methode) und das
 *       Segment davor gross beginnt (Klassenname) — {@code application.yml} bleibt also ganz,</li>
 *   <li>Subjects mit {@code /} sind Pfade und bleiben unangetastet,</li>
 *   <li>Rand-Leerzeichen werden getrimmt, die Gross-/Kleinschreibung bleibt unveraendert.</li>
 * </ul>
 * Die Funktion ist idempotent: {@code normalize(normalize(x)).equals(normalize(x))}.
 */
public final class AuditSubjectNormalizer {

    /** Art-Wort vor dem Doppelpunkt: klein, ohne Punkt und Schraegstrich (also keine Datei, keine Klasse). */
    private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9-]*");

    private AuditSubjectNormalizer() {
    }

    public static String normalize(String subject) {
        if (subject == null) {
            return "";
        }
        String value = subject.trim();
        int colon = value.indexOf(':');
        if (colon >= 0) {
            String head = value.substring(0, colon).trim();
            if (KIND.matcher(head).matches()) {
                return head + ":" + value.substring(colon + 1).trim();
            }
            value = head;
        }
        if (value.isEmpty() || value.indexOf('/') >= 0) {
            return value;
        }
        int lastDot = value.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == value.length() - 1) {
            return value;
        }
        String head = value.substring(0, lastDot);
        if (!startsLowerCase(value.substring(lastDot + 1))) {
            return value;
        }
        String headSegment = head.substring(head.lastIndexOf('.') + 1);
        return startsUpperCase(headSegment) ? head : value;
    }

    private static boolean startsLowerCase(String value) {
        return !value.isEmpty() && Character.isLowerCase(value.charAt(0));
    }

    private static boolean startsUpperCase(String value) {
        return !value.isEmpty() && Character.isUpperCase(value.charAt(0));
    }
}
