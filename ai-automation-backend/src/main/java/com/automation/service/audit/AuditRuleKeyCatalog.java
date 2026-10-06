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
  * Catalog of the allowed {@code ruleKey}s per {@code auditKey} (resource
  * {@code audit/rule-keys.json}, collected from the audit prompts in {@code workflow-pool/}).
 *
  * <p>The identity of a finding hangs on {@code ruleKey} + {@code subject}: if the
  * ruleKey drifts between two runs ({@code god-classes-count} instead of {@code god-class-count}), a
  * revenant finding arises including artifact counts in the self-improvement loop (audit TODO 8fc9a401).
  * Therefore an unknown key is first {@link #normalize(String) normalized}; if it still does
  * not fit then, the finding is taken over anyway (no data loss) and the case is
  * reported. An auditKey without a catalog entry is not checked.
 *
  * <p>An entry is either the key as a string or {@code {"key": …, "subject": …}}: if a
  * rule concerns the repository or a module as a whole, its subject is fixed ({@link #fixedSubject}), and the
  * ingestion enforces it instead of relying on the request in the prompt (TODO 802f740a). File-related
  * rules have no fixed value; there the path rule of the prompt applies.
 */
@Slf4j
@Component
public class AuditRuleKeyCatalog {

    private static final String RESOURCE = "audit/rule-keys.json";
    private static final String COUNT_SUFFIX = "-count";

    private final Map<String, Set<String>> allowed;
    /** auditKey → ruleKey → fixed subject, only for rules with a fixed value. */
    private final Map<String, Map<String, String>> fixedSubjects;

    public AuditRuleKeyCatalog(ObjectMapper objectMapper) {
        Catalog catalog = load(objectMapper);
        this.allowed = catalog.allowed();
        this.fixedSubjects = catalog.fixedSubjects();
    }

    /**
     * @param auditKey   audit in whose namespace the key applies
     * @param rawRuleKey ruleKey from the envelope
     * @return the key to use and whether it remained unknown
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

    /** Is this auditKey checked at all? */
    public boolean isChecked(String auditKey) {
        return allowed.containsKey(auditKey);
    }

    /** Allowed keys of an audit (empty if the auditKey is not in the catalog). */
    public Set<String> allowedKeys(String auditKey) {
        return allowed.getOrDefault(auditKey, Set.of());
    }

    /**
     * The fixed subject of a rule that concerns the repository or a module as a whole.
     *
     * @param ruleKey the already resolved key ({@link #resolve})
     * @return the fixed value or {@code null} if the rule has none (file-related, unknown)
     */
    public String fixedSubject(String auditKey, String ruleKey) {
        return fixedSubjects.getOrDefault(auditKey, Map.of()).get(ruleKey);
    }

    /**
     * Compensate spelling drift: lowercase, {@code _} → {@code -}, and a plural before
     * {@code -count} becomes the singular ({@code god-classes-count} → {@code god-class-count}).
     */
    public static String normalize(String ruleKey) {
        if (ruleKey == null) {
            return null;
        }
        return singularizeBeforeCount(ruleKey.trim().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    /** Metric keys drift between singular and plural: {@code …-classes-count} → {@code …-class-count}. */
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

    /** After sibilants and s-sounds the plural is {@code -es} ({@code class → classes}). */
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
            // Without a catalog nothing is checked — that must never prevent the start.
            log.error("Audit rule-key catalog {} could not be read, ruleKeys stay unchecked: {}", RESOURCE, e.toString());
            return new Catalog(Map.of(), Map.of());
        }
    }

    private record Catalog(Map<String, Set<String>> allowed, Map<String, Map<String, String>> fixedSubjects) {}

    /**
     * @param ruleKey the key to use (normalized, otherwise the original)
     * @param unknown true if the key is not in the catalog even normalized
     */
    public record Resolution(String ruleKey, boolean unknown) {}
}
