package com.automation.service.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Katalog der erlaubten {@code ruleKey}s je {@code auditKey} (Ressource
 * {@code audit/rule-keys.json}, erhoben aus den Audit-Prompts im {@code workflow-pool/}).
 *
 * <p>Die Identitaet eines Findings haengt an {@code ruleKey} + {@code subject}: driftet der
 * ruleKey zwischen zwei Runs ({@code god-classes-count} statt {@code god-class-count}), entsteht
 * ein Wiedergaenger-Finding samt Artefakt-Zahlen im Self-Improvement-Loop (Audit-TODO 8fc9a401).
 * Deshalb wird ein unbekannter Key erst {@link #normalize(String) normalisiert}; passt er dann
 * immer noch nicht, wird das Finding trotzdem uebernommen (kein Datenverlust) und der Fall
 * gemeldet. Ein auditKey ohne Katalogeintrag wird nicht geprueft.
 *
 * <p>Ein Eintrag ist entweder der Key als String oder {@code {"key": …, "subject": …}}: Betrifft eine
 * Regel das Repository oder ein Modul als Ganzes, steht ihr subject fest ({@link #fixedSubject}), und die
 * Ingestion setzt es durch, statt sich auf die Bitte im Prompt zu verlassen (TODO 802f740a). Dateibezogene
 * Regeln haben keinen festen Wert; dort gilt die Pfadregel des Prompts.
 */
@Slf4j
@Component
public class AuditRuleKeyCatalog {

    private static final String RESOURCE = "audit/rule-keys.json";
    private static final String COUNT_SUFFIX = "-count";

    private final Map<String, Set<String>> allowed;
    /** auditKey → ruleKey → festes subject, nur fuer Regeln mit festem Wert. */
    private final Map<String, Map<String, String>> fixedSubjects;

    public AuditRuleKeyCatalog(ObjectMapper objectMapper) {
        Catalog catalog = load(objectMapper);
        this.allowed = catalog.allowed();
        this.fixedSubjects = catalog.fixedSubjects();
    }

    /**
     * @param auditKey   Audit, in dessen Namensraum der Key gilt
     * @param rawRuleKey ruleKey aus dem Envelope
     * @return der zu verwendende Key und ob er unbekannt blieb
     */
    public Resolution resolve(String auditKey, String rawRuleKey) {
        Set<String> known = allowed.get(auditKey);
        if (known == null || rawRuleKey == null || known.contains(rawRuleKey)) {
            return new Resolution(rawRuleKey, false);
        }
        String normalized = normalize(rawRuleKey);
        if (known.contains(normalized)) {
            return new Resolution(normalized, false);
        }
        return new Resolution(rawRuleKey, true);
    }

    /** Wird dieser auditKey ueberhaupt geprueft? */
    public boolean isChecked(String auditKey) {
        return allowed.containsKey(auditKey);
    }

    /** Erlaubte Keys eines Audits (leer, wenn der auditKey nicht im Katalog steht). */
    public Set<String> allowedKeys(String auditKey) {
        return allowed.getOrDefault(auditKey, Set.of());
    }

    /**
     * Das feste subject einer Regel, die das Repository oder ein Modul als Ganzes betrifft.
     *
     * @param ruleKey der schon aufgeloeste Key ({@link #resolve})
     * @return der feste Wert oder {@code null}, wenn die Regel keinen hat (dateibezogen, unbekannt)
     */
    public String fixedSubject(String auditKey, String ruleKey) {
        return fixedSubjects.getOrDefault(auditKey, Map.of()).get(ruleKey);
    }

    /**
     * Schreibweisen-Drift ausgleichen: Kleinschreibung, {@code _} → {@code -}, und ein Plural vor
     * {@code -count} wird zum Singular ({@code god-classes-count} → {@code god-class-count}).
     */
    public static String normalize(String ruleKey) {
        if (ruleKey == null) {
            return null;
        }
        return singularizeBeforeCount(ruleKey.trim().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    /** Metrik-Keys driften zwischen Einzahl und Mehrzahl: {@code …-classes-count} → {@code …-class-count}. */
    private static String singularizeBeforeCount(String ruleKey) {
        int cut = ruleKey.length() - COUNT_SUFFIX.length();
        if (cut <= 0 || !ruleKey.endsWith(COUNT_SUFFIX)) {
            return ruleKey;
        }
        String stem = ruleKey.substring(0, cut);
        if (stem.endsWith("es") && endsSibilant(stem.substring(0, stem.length() - 2))) {
            stem = stem.substring(0, stem.length() - 2);
        } else if (stem.endsWith("s") && !stem.endsWith("ss")) {
            stem = stem.substring(0, stem.length() - 1);
        }
        return stem + COUNT_SUFFIX;
    }

    /** Nach Zisch- und S-Lauten lautet der Plural {@code -es} ({@code class → classes}). */
    private static boolean endsSibilant(String stem) {
        return stem.endsWith("s") || stem.endsWith("x") || stem.endsWith("z")
                || stem.endsWith("ch") || stem.endsWith("sh");
    }

    private static Catalog load(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            Map<String, Set<String>> allowed = new LinkedHashMap<>();
            Map<String, Map<String, String>> fixed = new LinkedHashMap<>();
            root.fields().forEachRemaining(audit -> {
                if (audit.getKey().startsWith("_")) {
                    return;
                }
                Set<String> keys = new LinkedHashSet<>();
                Map<String, String> subjects = new LinkedHashMap<>();
                for (JsonNode entry : audit.getValue()) {
                    String key = entry.isObject() ? entry.path("key").asText() : entry.asText();
                    keys.add(key);
                    if (entry.hasNonNull("subject")) {
                        subjects.put(key, entry.get("subject").asText());
                    }
                }
                allowed.put(audit.getKey(), Set.copyOf(keys));
                fixed.put(audit.getKey(), Map.copyOf(subjects));
            });
            return new Catalog(Map.copyOf(allowed), Map.copyOf(fixed));
        } catch (Exception e) {
            // Ohne Katalog wird nicht geprueft — das darf den Start nie verhindern.
            log.error("Audit rule-key catalog {} could not be read, ruleKeys stay unchecked: {}", RESOURCE, e.toString());
            return new Catalog(Map.of(), Map.of());
        }
    }

    private record Catalog(Map<String, Set<String>> allowed, Map<String, Map<String, String>> fixedSubjects) {}

    /**
     * @param ruleKey der zu verwendende Key (normalisiert, sonst der urspruengliche)
     * @param unknown true, wenn der Key auch normalisiert nicht im Katalog steht
     */
    public record Resolution(String ruleKey, boolean unknown) {}
}
