package com.automation.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * {@code read_guide}: the platform's own documentation for clients that use tools only — the same files
 * {@link McpResources} offers, read in two steps (headings, then one section).
 */
@Component
@RequiredArgsConstructor
public class McpGuideTools implements McpToolset {

    private final McpGuides guides;
    private final ObjectMapper objectMapper;

    @Override
    public List<McpTool> tools() {
        return List.of(McpTool.reading("read_guide", "Read guide",
                "Reads the platform's own documentation. Without 'section' it returns the list of headings; with "
                        + "'section' (part of a heading, e.g. 'Frugality Ladder', 'Audit Pipeline', 'Approval Gate', "
                        + "'Dos and Don') only that section. Topics: " + topics(),
                McpSchema.object()
                        .enumeration("topic", "Which guide",
                                Arrays.stream(McpGuides.Guide.values()).map(McpGuides.Guide::topic).toList(), true)
                        .string("section", "Part of a heading; omit for the list of headings", false)
                        .enumeration("lang", "Language (default en)", List.of("de", "en"), false)
                        .build(),
                this::readGuide));
    }

    private McpToolResult readGuide(McpArgs args) {
        String topic = args.string("topic");
        McpGuides.Guide guide = McpGuides.Guide.byTopic(topic)
                .orElseThrow(() -> new McpArgs.InvalidArgument("Unknown topic '" + topic + "' — known: " + topics()));
        String markdown = guides.read(guide, args.locale("lang")).orElse(null);
        if (markdown == null) {
            return McpToolResult.error("This installation ships no documentation directory for '" + topic + "'.");
        }
        ObjectNode data = objectMapper.createObjectNode();
        data.put("topic", topic);
        String section = args.optionalString("section");
        if (section == null || section.isBlank()) {
            ArrayNode headings = data.putArray("headings");
            McpGuides.headings(markdown).forEach(headings::add);
            data.put("hint", "Call read_guide again with 'section' set to part of a heading.");
            return McpToolResult.ok(data);
        }
        return McpGuides.section(markdown, section)
                .map(text -> McpToolResult.ok(data.put("section", text)))
                .orElseGet(() -> McpToolResult.error("No heading in '" + topic + "' contains '" + section
                        + "' — call read_guide without 'section' for the list of headings."));
    }

    private static String topics() {
        StringBuilder sb = new StringBuilder();
        for (McpGuides.Guide g : McpGuides.Guide.values()) {
            sb.append(sb.isEmpty() ? "" : "; ").append(g.topic()).append(" — ").append(g.summary());
        }
        return sb.toString();
    }
}
