package com.automation.service.assistant.fetch;

import com.automation.executor.spec.BlockSpecs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Fetch handler for static reference documentation: bundled docs with
 * per-section paging ({@code docs}) and the block type specifications ({@code block-spec},
 * texts in {@link BlockSpecs}).
 */
@Component
@Slf4j
public class DocsFetchHandler {

    private static final Map<String, String> DOC_FILES = Map.of(
            "workflow-guide", "docs/WORKFLOW-GUIDE.md",
            "architektur",    "docs/architektur.md"
    );

    private static final Map<String, String> WF_GUIDE_SECTIONS = Map.ofEntries(
            Map.entry("quick-start",    "## 1."),
            Map.entry("block-types",    "## 2."),
            Map.entry("variables",      "## 3."),
            Map.entry("patterns",       "## 4."),
            Map.entry("timeouts",       "## 5."),
            Map.entry("models",         "## 6."),
            Map.entry("environment",    "## 7."),
            Map.entry("agentic",        "## 8."),
            Map.entry("memory",         "## 9."),
            Map.entry("chains",         "## 10."),
            Map.entry("dos-and-donts",  "## 11."),
            Map.entry("appendix",       "## Anhang")
    );

    public String formatDocsSection(String docId) {
        String baseId = docId;
        String sectionKey = null;
        int slash = docId.indexOf('/');
        if (slash >= 0) {
            baseId = docId.substring(0, slash);
            sectionKey = docId.substring(slash + 1);
        }

        String filename = DOC_FILES.get(baseId);
        if (filename == null) return "Unknown documentation: " + baseId + "\n";

        try {
            var resource = new ClassPathResource(filename);
            String fullContent = resource.getContentAsString(StandardCharsets.UTF_8);

            if (sectionKey == null) {
                return buildDocsToc(baseId, fullContent);
            }

            String sectionHeader = "workflow-guide".equals(baseId)
                    ? WF_GUIDE_SECTIONS.get(sectionKey) : null;
            if (sectionHeader == null) {
                String content = fullContent.length() > 8000
                        ? fullContent.substring(0, 8000) + "\n\n[Output truncated: 8000 of " + fullContent.length() + " characters]" : fullContent;
                return "**Documentation: " + baseId + "**\n\n" + content + "\n";
            }

            String section = extractSection(fullContent, sectionHeader);
            if (section.isBlank()) return "Section '" + sectionKey + "' not found in " + baseId + ".\n";
            if (section.length() > 12000) section = section.substring(0, 12000) + "\n\n[Output truncated: 12000 of " + section.length() + " characters]";
            return "**" + baseId + " / " + sectionKey + ":**\n\n" + section + "\n";

        } catch (Exception e) {
            log.debug("Could not load doc '{}': {}", docId, e.getMessage());
            return "Documentation '" + docId + "' not available.\n";
        }
    }

    private String buildDocsToc(String docId, String content) {
        var sb = new StringBuilder();
        sb.append("**Documentation: ").append(docId).append("** — Table of contents\n\n");
        boolean inFence = false;
        for (String line : content.split("\n")) {
            if (line.startsWith("```")) inFence = !inFence;
            else if (!inFence && line.startsWith("## ")) sb.append(line).append("\n");
        }
        if ("workflow-guide".equals(docId)) {
            sb.append("\nAvailable section IDs for `<fetch type=\"docs\" id=\"workflow-guide/<id>\"/>`:\n");
            WF_GUIDE_SECTIONS.forEach((k, v) -> sb.append("  ").append(k).append("\n"));
        }
        sb.append("\nFetch a section to get details.\n");
        return sb.toString();
    }

    static String extractSection(String content, String headerPrefix) {
        String[] lines = content.split("\n");
        var sb = new StringBuilder();
        boolean inSection = false;
        boolean inFence = false; // "## …" in einem Codeblock ist Beispieltext, keine Überschrift
        for (String line : lines) {
            if (line.startsWith("```")) {
                inFence = !inFence;
            } else if (!inFence) {
                if (!inSection) {
                    if (line.startsWith(headerPrefix)) inSection = true;
                } else if (line.startsWith("## ") && !line.startsWith(headerPrefix)) {
                    break;
                }
            }
            if (inSection) sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    /** The assistant's {@code <fetch type="block-spec" id="…"/>}; the texts live in {@link BlockSpecs}. */
    public String formatBlockSpec(String blockType) {
        return BlockSpecs.format(blockType);
    }
}
