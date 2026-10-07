package com.automation.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Guided procedures a client can offer its user (Claude Code shows them as slash commands). The texts
 * live in {@code mcp/prompts/<name>.md} and point to the guide sections instead of repeating them.
 */
@Component
public class McpPrompts {

    record Argument(String name, String description, boolean required) {}

    record Prompt(String name, String title, String description, List<Argument> arguments) {}

    private static final Argument PROJECT = new Argument("project_id", "Project to build it in (optional)", false);

    static final List<Prompt> PROMPTS = List.of(
            new Prompt("design_workflow", "Design a workflow",
                    "Design a workflow for a goal step by step: starting point, patterns, deterministic first, "
                            + "check, test run.",
                    List.of(new Argument("goal", "What the workflow should achieve", true), PROJECT)),
            new Prompt("review_workflow", "Review a workflow",
                    "Review a workflow against the patterns: what could be deterministic, cheaper, safer.",
                    List.of(new Argument("workflow_id", "Workflow id", true))),
            new Prompt("audit_workflow", "Set up an audit",
                    "Set up an audit workflow: measure with shell, judge only candidates, report with evidence.",
                    List.of(new Argument("subject", "What to audit, e.g. 'dependencies of the backend'", true),
                            PROJECT)));

    /** The answer of {@code prompts/list}. */
    ObjectNode list() {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ArrayNode prompts = result.putArray("prompts");
        for (Prompt p : PROMPTS) {
            ObjectNode node = prompts.addObject();
            node.put("name", p.name());
            node.put("title", p.title());
            node.put("description", p.description());
            ArrayNode args = node.putArray("arguments");
            for (Argument a : p.arguments()) {
                args.addObject().put("name", a.name()).put("description", a.description()).put("required", a.required());
            }
        }
        return result;
    }

    /**
     * The answer of {@code prompts/get}: the text with its arguments filled in.
     *
     * @throws McpArgs.InvalidArgument for an unknown prompt or a missing required argument
     */
    ObjectNode get(String name, JsonNode arguments) {
        Prompt prompt = find(name).orElseThrow(() -> new McpArgs.InvalidArgument("Unknown prompt '" + name + "'"));
        String text = template(name);
        for (Argument a : prompt.arguments()) {
            JsonNode value = arguments == null ? null : arguments.get(a.name());
            String v = value == null || value.isNull() ? "" : value.asText().strip();
            if (a.required() && v.isEmpty()) {
                throw new McpArgs.InvalidArgument("Prompt '" + name + "' needs the argument '" + a.name() + "'");
            }
            if (a == PROJECT) {
                text = text.replace("{{project_line}}", v.isEmpty() ? "" : "Project: " + v + "\n");
            } else {
                text = text.replace("{{" + a.name() + "}}", v);
            }
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("description", prompt.description());
        ObjectNode message = result.putArray("messages").addObject();
        message.put("role", "user");
        message.putObject("content").put("type", "text").put("text", text);
        return result;
    }

    private static Optional<Prompt> find(String name) {
        return PROMPTS.stream().filter(p -> p.name().equals(name)).findFirst();
    }

    private static String template(String name) {
        try {
            return new ClassPathResource("mcp/prompts/" + name + ".md").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Prompt text missing: " + name, e);
        }
    }
}
