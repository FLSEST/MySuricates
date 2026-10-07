package com.automation.service.mcp;

import com.automation.config.DocsConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The documentation an MCP client can read: the same Markdown files as the docs page of the UI
 * ({@code automation.docs.directory}, German there, English under {@code en/}). No second version — the
 * patterns a client should follow (frugality ladder, audit pipeline, approval gate) live in the
 * workflow guide and are read from there.
 *
 * <p>Large files are read in two steps (progressive disclosure): first the list of headings, then one
 * section.
 */
@Component
@RequiredArgsConstructor
public class McpGuides {

    /** A guide a client may read; {@code file} is the same name in both languages. */
    public enum Guide {
        WORKFLOW_GUIDE("workflow-guide", "WORKFLOW-GUIDE.md",
                "How to build workflows: block types, variables, proven patterns (frugality ladder, audit "
                        + "pipeline, approval gate), timeouts, model choice, dos and don'ts."),
        BLOCK_REFERENCE("block-reference", "block-referenz.md",
                "Every config key of every block type (generated from the block schema)."),
        BUILDING_BLOCKS("building-blocks", "baukasten.md",
                "What the platform is for: core, optional modules, what to connect, approaches.");

        private final String topic;
        private final String file;
        private final String summary;

        Guide(String topic, String file, String summary) {
            this.topic = topic;
            this.file = file;
            this.summary = summary;
        }

        public String topic() {
            return topic;
        }

        public String file() {
            return file;
        }

        public String summary() {
            return summary;
        }

        public static Optional<Guide> byTopic(String topic) {
            for (Guide guide : values()) {
                if (guide.topic.equals(topic)) {
                    return Optional.of(guide);
                }
            }
            return Optional.empty();
        }
    }

    private final DocsConfig docsConfig;

    /** The whole file; empty if this installation ships no docs directory. */
    public Optional<String> read(Guide guide, String lang) {
        Path dir = Path.of(docsConfig.getDirectory()).toAbsolutePath().normalize();
        Path file = ("en".equals(lang) ? dir.resolve("en") : dir).resolve(guide.file());
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    /** The headings ({@code ##} and {@code ###}) outside code blocks, in order. */
    public static List<String> headings(String markdown) {
        List<String> result = new ArrayList<>();
        boolean inCode = false;
        for (String line : markdown.split("\n", -1)) {
            if (line.startsWith("```")) {
                inCode = !inCode;
            } else if (!inCode && (line.startsWith("## ") || line.startsWith("### "))) {
                result.add(line.strip());
            }
        }
        return result;
    }

    /**
     * The section whose heading contains {@code query} (case-insensitive, first match): from that heading
     * to the next heading of the same or a higher level. Empty if no heading matches.
     */
    public static Optional<String> section(String markdown, String query) {
        String wanted = query.strip().toLowerCase(Locale.ROOT);
        String[] lines = markdown.split("\n", -1);
        boolean inCode = false;
        boolean found = false;
        int level = 0;
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("```")) {
                inCode = !inCode;
            }
            int lineLevel = inCode ? 0 : headingLevel(line);
            if (!found) {
                if (lineLevel > 0 && line.toLowerCase(Locale.ROOT).contains(wanted)) {
                    found = true;
                    level = lineLevel;
                    out.append(line).append('\n');
                }
                continue;
            }
            if (lineLevel > 0 && lineLevel <= level) {
                break;
            }
            out.append(line).append('\n');
        }
        return found ? Optional.of(out.toString().strip()) : Optional.empty();
    }

    private static int headingLevel(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        return level > 0 && level < line.length() && line.charAt(level) == ' ' ? level : 0;
    }
}
