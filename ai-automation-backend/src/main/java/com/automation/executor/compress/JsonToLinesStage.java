package com.automation.executor.compress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * json.to_lines — one line per array entry from a template with single-brace fields ("{title} — {url}").
 * Single braces on purpose: {{ }} would be resolved again by the TemplateResolver downstream.
 */
final class JsonToLinesStage implements CompressionStage {

    private static final Pattern FIELD = Pattern.compile("\\{([A-Za-z0-9_.\\-]+)\\}");

    @Override
    public String op() {
        return "json.to_lines";
    }

    @Override
    public StageKind inputKind() {
        return StageKind.JSON;
    }

    @Override
    public StageKind outputKind() {
        return StageKind.TEXT;
    }

    @Override
    public void validate(Map<String, Object> config) {
        template(config);
        StageConfig.string(config, "path", "", StageConfig.where(op()));
    }

    @Override
    public StageResult apply(String input, Map<String, Object> config, RegexGuard guard) {
        JsonNode root = JsonPaths.parse(input, op());
        ArrayNode array = JsonPaths.arrayAt(root, StageConfig.string(config, "path", "", StageConfig.where(op())), op());
        String template = template(config);
        List<String> lines = new ArrayList<>();
        for (JsonNode element : array) {
            Matcher m = FIELD.matcher(template);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                JsonNode value = JsonPaths.at(element, m.group(1));
                String text = JsonPaths.missing(value) ? "" : value.isValueNode() ? value.asText() : value.toString();
                m.appendReplacement(sb, Matcher.quoteReplacement(text));
            }
            m.appendTail(sb);
            lines.add(sb.toString().replace('\n', ' '));
        }
        return new StageResult(String.join("\n", lines), CompressionNumbers.fmt(lines.size()) + " Einträge als Zeilen");
    }

    private String template(Map<String, Object> config) {
        String where = StageConfig.where(op());
        String template = StageConfig.string(config, "template", null, where);
        if (!FIELD.matcher(template).find()) {
            throw new CompressionException(where + ": 'template' braucht mindestens einen Platzhalter wie {title}");
        }
        return template;
    }
}
