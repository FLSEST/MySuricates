package com.automation.service.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
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
 */
@Slf4j
@Component
public class AuditRuleKeyCatalog {

    private static final String RESOURCE = "audit/rule-keys.json";
    private static final String COUNT_SUFFIX = "-count";

    private final Map<String, Set<String>> allowed;

    public AuditRuleKeyCatalog(ObjectMapper objectMapper) {
        this.allowed = load(objectMapper);
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

    private static Map<String, Set<String>> load(ObjectMapper objectMapper) {
        MapType type = objectMapper.getTypeFactory()
                .constructMapType(LinkedHashMap.class, objectMapper.getTypeFactory().constructType(String.class),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            Map<String, List<String>> raw = objectMapper.readValue(in, type);
            Map<String, Set<String>> result = new LinkedHashMap<>();
            raw.forEach((auditKey, keys) -> {
                if (!auditKey.startsWith("_")) {
                    result.put(auditKey, Set.copyOf(keys));
                }
            });
            return Map.copyOf(result);
        } catch (Exception e) {
            // Ohne Katalog wird nicht geprueft — das darf den Start nie verhindern.
            log.error("Audit rule-key catalog {} could not be read, ruleKeys stay unchecked: {}", RESOURCE, e.toString());
            return Map.of();
        }
    }

    /**
     * @param ruleKey der zu verwendende Key (normalisiert, sonst der urspruengliche)
     * @param unknown true, wenn der Key auch normalisiert nicht im Katalog steht
     */
    public record Resolution(String ruleKey, boolean unknown) {}
}
