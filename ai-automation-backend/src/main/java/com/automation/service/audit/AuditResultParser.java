package com.automation.service.audit;

import com.automation.entity.AuditSeverity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class AuditResultParser {
    private static final Pattern ENVELOPE = Pattern.compile(
            "<!--\\s*mysuricates:audit-result:v1\\s*(\\{.*?})\\s*-->", Pattern.DOTALL);
    /**
     * This is how an envelope begins, even one that is never closed: marker and JSON start. A marker without
     * JSON behind it, e.g. quoted in a postscript, is not a beginning.
     */
    private static final Pattern ENVELOPE_START = Pattern.compile("<!--\\s*mysuricates:audit-result:v1\\s*\\{");
    private static final String MISSING = "Audit result envelope v1 is missing";
    private static final String INCOMPLETE = "The last audit result envelope v1 is incomplete or unreadable";

    private final ObjectMapper objectMapper;

    public ParseResult parse(String output) {
        String source = output == null ? "" : output;
        Envelope envelope = lastEnvelope(source);
        if (envelope.error() != null) {
            return ParseResult.invalid(withoutEnvelopes(source).trim(), envelope.error());
        }

        Matcher matcher = envelope.matcher();
        String markdown = (withoutEnvelopes(source.substring(0, matcher.start()))
                + source.substring(matcher.end())).trim();
        try {
            JsonNode root = envelope.root();
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
                    severity = AuditSeverity.valueOf(requiredText(node, "severity", i).toUpperCase(Locale.ROOT));
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
            // Optional completeness promise (TODO e39379a9): if the envelope names an
            // expected count, it must match. The gate block checks that in the workflow and reports
            // RED — the ingestion carried on unimpressed and, for a truncated
            // snapshot, set all findings not delivered to RESOLVED: exactly the failure pattern
            // that snapshot mode is meant to prevent. If the field is missing, everything stays as before.
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
     * The last readable envelope. Its own one closes the answer according to the prompt; before it there may be a
     * replayed lead-in: {@code previous_report} is the first 3000 characters of the last
     * report, for short reports including the envelope, also truncated. With the first envelope the
     * old snapshot counted.
     *
     * <p>The search therefore goes from the back, and a candidate only counts with readable JSON without remainder: a
     * quoted envelope start in the text of a finding extends to the end of the own envelope but yields
     * no JSON – then the enclosing one applies. If another envelope begins after the one found, the
     * answer is truncated or unreadable: then none applies, instead of falling back to an old one.
     */
    private Envelope lastEnvelope(String source) {
        List<Integer> starts = new ArrayList<>();
        Matcher start = ENVELOPE_START.matcher(source);
        while (start.find()) {
            starts.add(start.start());
        }
        if (starts.isEmpty()) {
            return Envelope.failed(MISSING);
        }
        String unreadable = null;
        for (int i = starts.size() - 1; i >= 0; i--) {
            Matcher envelope = ENVELOPE.matcher(source).region(starts.get(i), source.length());
            if (!envelope.lookingAt()) {
                continue;
            }
            JsonNode root;
            try {
                root = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(envelope.group(1));
            } catch (JsonProcessingException ex) {
                unreadable = unreadable != null ? unreadable : ex.getMessage();
                continue;
            }
            if (starts.get(starts.size() - 1) >= envelope.end()) {
                break;   // another one begins after it that does not end readably
            }
            return new Envelope(envelope, root, null);
        }
        return Envelope.failed(unreadable != null ? INCOMPLETE + ": " + unreadable : INCOMPLETE);
    }

    /** The envelope found including JSON, or the reason why there is none. */
    private record Envelope(Matcher matcher, JsonNode root, String error) {
        static Envelope failed(String error) {
            return new Envelope(null, null, error);
        }
    }

    /** The text without complete envelopes – also without a replayed old one. */
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
