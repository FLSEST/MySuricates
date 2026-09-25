package com.automation.service.audit;

import com.automation.entity.AuditSeverity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class AuditResultParser {
    private static final Pattern ENVELOPE = Pattern.compile(
            "<!--\\s*mysuricates:audit-result:v1\\s*(\\{.*?})\\s*-->", Pattern.DOTALL);
    /** Der Anfang eines Envelopes, auch eines, der nie geschlossen wird. */
    private static final Pattern ENVELOPE_START = Pattern.compile("<!--\\s*mysuricates:audit-result:v1");

    private final ObjectMapper objectMapper;

    public ParseResult parse(String output) {
        String source = output == null ? "" : output;
        Matcher matcher = lastEnvelope(source);
        if (matcher == null) {
            return ParseResult.invalid(withoutEnvelopes(source).trim(), ENVELOPE_START.matcher(source).find()
                    ? "Audit result envelope v1 at the end of the output is not closed"
                    : "Audit result envelope v1 is missing");
        }

        String envelopeJson = matcher.group(1);
        String markdown = (withoutEnvelopes(source.substring(0, matcher.start()))
                + source.substring(matcher.end())).trim();
        try {
            JsonNode root = objectMapper.readTree(envelopeJson);
            if (!root.path("snapshotComplete").asBoolean(false)) {
                return ParseResult.invalid(markdown, "snapshotComplete must be true");
            }
            JsonNode findingsNode = root.get("findings");
            if (findingsNode == null || !findingsNode.isArray()) {
                return ParseResult.invalid(markdown, "findings must be an array");
            }

            List<ParsedFinding> findings = new ArrayList<>();
            for (int i = 0; i < findingsNode.size(); i++) {
                JsonNode node = findingsNode.get(i);
                String ruleKey = requiredText(node, "ruleKey", i);
                String subject = requiredText(node, "subject", i);
                String title = requiredText(node, "title", i);
                AuditSeverity severity;
                try {
                    severity = AuditSeverity.valueOf(requiredText(node, "severity", i).toUpperCase());
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("findings[" + i + "].severity is invalid");
                }
                List<String> tags = new ArrayList<>();
                JsonNode tagsNode = node.get("tags");
                if (tagsNode != null && tagsNode.isArray()) {
                    tagsNode.forEach(tag -> {
                        if (tag.isTextual() && !tag.asText().isBlank()) tags.add(tag.asText());
                    });
                }
                findings.add(new ParsedFinding(ruleKey, subject, severity, title,
                        optionalText(node, "description"), optionalText(node, "evidence"), List.copyOf(tags)));
            }
            // Optionales Vollstaendigkeits-Versprechen (TODO e39379a9): Nennt der Envelope eine
            // erwartete Zahl, muss sie stimmen. Der Gate-Block prueft das im Workflow und meldet
            // RED — die Ingestion lief davon unbeeindruckt weiter und hat bei einem gekuerzten
            // Snapshot alle nicht gelieferten Findings auf RESOLVED gesetzt: genau das Fehlerbild,
            // das der Snapshot-Modus verhindern soll. Fehlt das Feld, bleibt alles wie bisher.
            JsonNode expected = root.get("expectedFindings");
            if (expected != null && !expected.isNull()) {
                if (!expected.isInt() && !expected.canConvertToInt()) {
                    return ParseResult.invalid(markdown, "expectedFindings must be a number");
                }
                if (expected.asInt() != findings.size()) {
                    return ParseResult.invalid(markdown, "expectedFindings says " + expected.asInt()
                            + ", envelope carries " + findings.size());
                }
            }
            return new ParseResult(true, null, markdown, List.copyOf(findings));
        } catch (Exception ex) {
            return ParseResult.invalid(markdown, ex.getMessage());
        }
    }

    /**
     * Der letzte vollstaendige Envelope, oder {@code null}. Der eigene schliesst laut Prompt die Antwort ab;
     * davor kann ein wiedergegebener Vorlauf stehen: {@code previous_report} sind die ersten 3000 Zeichen des
     * letzten Reports, bei kurzen Reports samt Envelope, auch abgeschnitten. Mit dem ersten Envelope zaehlte
     * der alte Snapshot. Steht hinter dem letzten vollstaendigen noch ein Envelope-Anfang, ist die Antwort
     * abgeschnitten: dann gilt keiner, statt auf den alten zurueckzufallen. Ein Anfang innerhalb des
     * gefundenen Envelopes (etwa im Text eines Findings) ist kein eigener.
     */
    private static Matcher lastEnvelope(String source) {
        List<Integer> starts = new ArrayList<>();
        Matcher start = ENVELOPE_START.matcher(source);
        while (start.find()) {
            starts.add(start.start());
        }
        for (int i = starts.size() - 1; i >= 0; i--) {
            Matcher envelope = ENVELOPE.matcher(source).region(starts.get(i), source.length());
            if (envelope.lookingAt()) {
                return starts.get(starts.size() - 1) < envelope.end() ? envelope : null;
            }
        }
        return null;
    }

    /** Der Text ohne vollstaendige Envelopes – auch ohne einen wiedergegebenen alten. */
    private static String withoutEnvelopes(String text) {
        return ENVELOPE.matcher(text).replaceAll("");
    }

    private static String requiredText(JsonNode node, String field, int index) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("findings[" + index + "]." + field + " is required");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    public record ParsedFinding(String ruleKey, String subject, AuditSeverity severity, String title,
                                String description, String evidence, List<String> tags) {}

    public record ParseResult(boolean valid, String error, String markdown, List<ParsedFinding> findings) {
        static ParseResult invalid(String markdown, String error) {
            return new ParseResult(false, error, markdown, List.of());
        }
    }
}
