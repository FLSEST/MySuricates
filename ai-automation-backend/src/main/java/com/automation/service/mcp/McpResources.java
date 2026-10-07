package com.automation.service.mcp;

import com.automation.mapper.WorkflowMapper;
import com.automation.service.execution.ExecutionQueryService;
import com.automation.service.workflow.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Readable content: the guides as fixed resources, workflows and runs through URI templates. A client
 * reads them on demand — nothing here costs context until it is read.
 */
@Component
@RequiredArgsConstructor
public class McpResources {

    static final String SCHEME = "mysuricates://";
    private static final List<String> LANGS = List.of("en", "de");
    private static final Pattern DOC = Pattern.compile("mysuricates://docs/(de|en)/([a-z-]+)");
    private static final Pattern WORKFLOW = Pattern.compile("mysuricates://workflows/([A-Za-z0-9-]+)");
    private static final Pattern RUN = Pattern.compile("mysuricates://runs/([A-Za-z0-9-]+)");

    /** Thrown for a URI that names nothing readable — the protocol's "resource not found". */
    static final class NotFound extends RuntimeException {
        NotFound(String uri) {
            super("Resource not found: " + uri);
        }
    }

    private final McpGuides guides;
    private final WorkflowService workflowService;
    private final WorkflowMapper workflowMapper;
    private final ExecutionQueryService executionQueryService;
    private final McpRunTools runTools;
    private final ObjectMapper objectMapper;

    /** The answer of {@code resources/list}: every guide in every language this installation ships. */
    ObjectNode list() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode resources = result.putArray("resources");
        for (McpGuides.Guide guide : McpGuides.Guide.values()) {
            for (String lang : LANGS) {
                if (guides.read(guide, lang).isEmpty()) {
                    continue;
                }
                ObjectNode node = resources.addObject();
                node.put("uri", SCHEME + "docs/" + lang + "/" + guide.topic());
                node.put("name", guide.topic() + "-" + lang);
                node.put("title", guide.topic() + " (" + lang + ")");
                node.put("description", guide.summary());
                node.put("mimeType", "text/markdown");
            }
        }
        return result;
    }

    /** The answer of {@code resources/templates/list}. */
    ObjectNode templates() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode list = result.putArray("resourceTemplates");
        list.addObject()
                .put("uriTemplate", SCHEME + "workflows/{id}")
                .put("name", "workflow")
                .put("title", "Workflow definition")
                .put("description", "The full definition of a workflow, as get_workflow returns it.")
                .put("mimeType", "application/json");
        list.addObject()
                .put("uriTemplate", SCHEME + "runs/{id}")
                .put("name", "run")
                .put("title", "Workflow run")
                .put("description", "A run with status, open question and step outputs, as get_run returns it.")
                .put("mimeType", "application/json");
        return result;
    }

    /** The answer of {@code resources/read}. */
    ObjectNode read(String uri) {
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode content = result.putArray("contents").addObject();
        content.put("uri", uri);
        Matcher m;
        if ((m = DOC.matcher(uri)).matches()) {
            String lang = m.group(1);
            String text = McpGuides.Guide.byTopic(m.group(2))
                    .flatMap(g -> guides.read(g, lang))
                    .orElseThrow(() -> new NotFound(uri));
            content.put("mimeType", "text/markdown");
            content.put("text", text);
        } else if ((m = WORKFLOW.matcher(uri)).matches()) {
            content.put("mimeType", "application/json");
            content.put("text", objectMapper.valueToTree(
                    workflowMapper.toResponse(workflowService.findById(m.group(1)))).toPrettyString());
        } else if ((m = RUN.matcher(uri)).matches()) {
            content.put("mimeType", "application/json");
            content.put("text", runTools.runView(executionQueryService.getExecutionResponse(m.group(1))).toPrettyString());
        } else {
            throw new NotFound(uri);
        }
        return result;
    }
}
