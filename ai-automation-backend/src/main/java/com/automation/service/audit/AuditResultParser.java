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

    private final ObjectMapper objectMapper;

    public ParseResult parse(String output) {
        String source = output == null ? "" : output;
        Matcher matcher = ENVELOPE.matcher(source);
        if (!matcher.find()) {
            return ParseResult.invalid(source.trim(), "Audit result envelope v1 is missing");
        }

        String envelopeJson = matcher.group(1);
        String markdown = matcher.replaceAll("").trim();
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
