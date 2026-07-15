package com.automation.service.assistant;

import com.automation.service.assistant.fetch.DocsFetchHandler;
import com.automation.service.assistant.fetch.InventoryFetchHandler;
import com.automation.service.assistant.fetch.PoolFetchHandler;
import com.automation.service.assistant.fetch.ProjectFetchHandler;
import com.automation.service.assistant.fetch.RunFetchHandler;
import com.automation.service.assistant.fetch.WorkflowFetchHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles dynamic fetch requests (the <fetch> tag mechanism) for assistant chat.
 * When the assistant's first response contains <fetch type="..." id="..."/> tags,
 * this service resolves them into supplemental context data.
 *
 * <p>Facade: parses/strips the fetch tags and routes each request to the focused
 * handler for its domain in {@link com.automation.service.assistant.fetch}.
 */
@Component
@RequiredArgsConstructor
public class AssistantFetchService {

    private static final Pattern FETCH_PATTERN =
            Pattern.compile("<fetch type=\"([^\"]+)\" id=\"([^\"]+)\"/>");

    private final RunFetchHandler runFetchHandler;
    private final WorkflowFetchHandler workflowFetchHandler;
    private final DocsFetchHandler docsFetchHandler;
    private final PoolFetchHandler poolFetchHandler;
    private final ProjectFetchHandler projectFetchHandler;
    private final InventoryFetchHandler inventoryFetchHandler;

    public record FetchRequest(String type, String id) {}

    public List<FetchRequest> parseFetchRequests(String response) {
        if (response == null) return List.of();
        List<FetchRequest> result = new ArrayList<>();
        Matcher m = FETCH_PATTERN.matcher(response);
        while (m.find()) result.add(new FetchRequest(m.group(1), m.group(2)));
        return result;
    }

    public String stripFetchTags(String response) {
        if (response == null) return response;
        return FETCH_PATTERN.matcher(response).replaceAll("").trim();
    }

    public String executeFetch(FetchRequest req) {
        return switch (req.type()) {
            case "run_detail"       -> runFetchHandler.formatRunDetail(req.id());
            case "wish_detail"      -> inventoryFetchHandler.formatWishDetail(req.id());
            case "workflow_runs"    -> runFetchHandler.formatWorkflowRuns(req.id());
            case "workflow_detail"  -> workflowFetchHandler.formatWorkflowDetail(req.id());
            case "workflow-full"    -> workflowFetchHandler.formatWorkflowFull(req.id());
            case "workflow-inputs"  -> workflowFetchHandler.formatWorkflowInputs(req.id());
            case "find_workflow"    -> workflowFetchHandler.formatFindWorkflow(req.id());
            case "step_output"      -> runFetchHandler.formatStepOutput(req.id());
            case "docs"             -> docsFetchHandler.formatDocsSection(req.id());
            case "pool_workflows"   -> poolFetchHandler.formatPoolWorkflows();
            case "pool_workflow"    -> poolFetchHandler.formatPoolWorkflowDetail(req.id());
            case "project_context"  -> projectFetchHandler.formatProjectContext(req.id());
            case "project_files"    -> projectFetchHandler.formatProjectFiles(req.id());
            case "project_file"     -> projectFetchHandler.formatProjectFile(req.id());
            case "skill"            -> inventoryFetchHandler.formatSkillDetail(req.id());
            case "integration"      -> inventoryFetchHandler.formatIntegrationDetail(req.id());
            case "chain"            -> inventoryFetchHandler.formatChainDetail(req.id());
            case "block-spec"       -> docsFetchHandler.formatBlockSpec(req.id());
            default -> "";
        };
    }
}
