package com.automation.service.audit;

import java.util.regex.Pattern;

/**
 * Normalisiert das modellgenerierte {@code subject} eines Audit-Findings, damit der Fingerprint
 * nicht bei jedem Run driftet (Audit-TODO 0e19648d). Beobachtete Drift: {@code ContainerShellService}
 * ↔ {@code ContainerShellService.execute}, {@code application.yml} ↔ {@code application.yml:key-store-password},
 * {@code AssistantService} ↔ {@code com/automation/service/AssistantService.java} (TODO 87a1dac0).
 *
 * <p>Reine Funktion, bewusst konservativ:
 * <ul>
 *   <li>steht vor dem ersten {@code :} ein klein geschriebenes Art-Wort ({@code package},
 *       {@code keyword}, {@code safe-inventory}), ist {@code <art>:<wert>} die Identitaet und bleibt
 *       ganz — sonst fielen {@code package:a} und {@code package:b} zusammen und die Ingestion verwarf
 *       den zweiten Befund (TODO 94fda6ef),</li>
 *   <li>sonst (Datei, Klasse, Pfad vor dem {@code :}) faellt alles ab dem ersten {@code :} weg
 *       (Schluessel, Zeilennummer),</li>
 *   <li>ein Pfad auf eine Java-Klasse ({@code .java}, gross beginnend) wird zum Klassennamen:
 *       Klassenname und Pfad sind dieselbe Identitaet, egal ob {@code com/automation/…}, {@code service/…}
 *       oder ohne Pfad (TODO 87a1dac0). Im Backend ist jeder einfache Klassenname eindeutig,</li>
 *   <li>ein voll qualifizierter Klassenname ({@code com.automation.service.AssistantService}) wird
 *       ebenso zum einfachen Klassennamen,</li>
 *   <li>ein Frontend-Modul ({@code .ts}, {@code .tsx}) bleibt ein Pfad, auch ohne Verzeichnis — sonst fiele
 *       {@code ActivityLog.tsx} mit der Java-Klasse {@code ActivityLog} zusammen, und klein geschriebene Module
 *       wie {@code index.ts} gibt es mehrfach. Angeglichen werden nur die Praefix-Varianten:
 *       {@code ai-automation-frontend/src/…} und {@code src/…} werden zu {@code frontend/src/…},</li>
 *   <li>ein Suffix hinter dem letzten {@code .} faellt weg, wenn es klein beginnt (Methode) und das
 *       Segment davor gross beginnt (Klassenname) — {@code application.yml} bleibt also ganz,</li>
 *   <li>alle anderen Subjects mit {@code /} sind Pfade und bleiben unangetastet,</li>
 *   <li>Rand-Leerzeichen werden getrimmt, die Gross-/Kleinschreibung bleibt unveraendert.</li>
 * </ul>
 * Die Funktion ist idempotent: {@code normalize(normalize(x)).equals(normalize(x))}.
 *
 * <p>Kennzahl-Regeln (ein Befund je Messwert, subject = FAKT-Name) stabilisiert nicht diese Funktion,
 * sondern der feste Wert im ruleKey-Katalog ({@link AuditRuleKeyCatalog#fixedSubject}).
 */
public final class AuditSubjectNormalizer {

    /** Art-Wort vor dem Doppelpunkt: klein, ohne Punkt und Schraegstrich (also keine Datei, keine Klasse). */
    private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9-]*");
    /** Java-Quelldatei, deren Name der Klassenname ist: {@code AssistantService.java}. */
    private static final Pattern CLASS_FILE = Pattern.compile("([A-Z][A-Za-z0-9_]*)\\.java");
    private static final String FRONTEND = "frontend/";
    private static final String FRONTEND_MODULE_DIR = "ai-automation-frontend/";
    /** Voll qualifizierter Klassenname: klein geschriebene Paketsegmente, dann der Klassenname. */
    private static final Pattern QUALIFIED_CLASS = Pattern.compile("(?:[a-z_][a-z0-9_]*\\.)+([A-Z][A-Za-z0-9_]*)");

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
        if (value.isEmpty()) {
            return value;
        }
        if (value.endsWith(".ts") || value.endsWith(".tsx")) {
            return frontendPath(value);
        }
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            var classFile = CLASS_FILE.matcher(value.substring(slash + 1));
            return classFile.matches() ? classFile.group(1) : value;
        }
        String withoutMethod = withoutMethodSuffix(value);
        var qualified = QUALIFIED_CLASS.matcher(withoutMethod);
        return qualified.matches() ? qualified.group(1) : withoutMethod;
    }

    /** Einheitliches Praefix fuer Frontend-Module: {@code frontend/src/…}. */
    private static String frontendPath(String value) {
        if (value.startsWith(FRONTEND_MODULE_DIR)) {
            return FRONTEND + value.substring(FRONTEND_MODULE_DIR.length());
        }
        return value.startsWith("src/") ? FRONTEND + value : value;
    }

    /** {@code Klasse.methode} → {@code Klasse}; {@code application.yml} und {@code Application.YML} bleiben. */
    private static String withoutMethodSuffix(String value) {
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
