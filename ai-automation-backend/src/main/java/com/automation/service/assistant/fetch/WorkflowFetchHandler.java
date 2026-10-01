package com.automation.service.assistant.fetch;

import com.automation.entity.BlockDefinition;
import com.automation.repository.WorkflowRepository;
import com.automation.service.assistant.format.AssistantFormatUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Fetch handler for the workflow domain: block summaries, full config dumps,
 * default inputs and name search ({@code workflow_detail}, {@code workflow-full},
 * {@code workflow-inputs}, {@code find_workflow}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowFetchHandler {

    private final WorkflowRepository workflowRepository;
    private final BlockConfigSummaryFormatter blockConfigSummaryFormatter;

    public String formatWorkflowDetail(String workflowId) {
        try {
            return workflowRepository.findById(workflowId).map(wf -> {
                var sb = new StringBuilder();
                String projectName = wf.getProject() != null ? wf.getProject().getName() : "—";
                String projectId = wf.getProject() != null ? wf.getProject().getId() : "—";
                sb.append("**Workflow: ").append(wf.getName()).append("** (").append(projectName).append(")\n");
                sb.append("Project ID: ").append(projectId).append("\n");
                if (wf.getDescription() != null && !wf.getDescription().isBlank()) {
                    sb.append("Description: ").append(AssistantFormatUtils.truncate(wf.getDescription().trim(), 150)).append("\n");
                }
                if (wf.getModel() != null && !wf.getModel().isBlank()) {
                    sb.append("Model: ").append(wf.getModel()).append("\n");
                }
                if (wf.getInstructions() != null && !wf.getInstructions().isBlank()) {
                    sb.append("Instructions: ").append(AssistantFormatUtils.truncate(wf.getInstructions().trim(), 400)).append("\n");
                }
                var blocks = wf.getBlocks();
                if (blocks != null && !blocks.isEmpty()) {
                    sb.append("\nBlocks (").append(blocks.size()).append("):\n");
                    Map<String, String> subWorkflowNames = blockConfigSummaryFormatter.subWorkflowNames(blocks);
                    for (int i = 0; i < blocks.size(); i++) {
                        BlockDefinition block = blocks.get(i);
                        Map<String, Object> cfg = block.getConfig() != null ? block.getConfig() : Map.of();
                        String label = cfg.containsKey("label") ? String.valueOf(cfg.get("label")) : "";
                        sb.append("  ").append(i + 1).append(". [").append(block.getType()).append("]");
                        if (!label.isBlank()) sb.append(" ").append(label);
                        sb.append("\n");
                        blockConfigSummaryFormatter.appendBlockConfigSummary(sb, block.getType(), cfg, subWorkflowNames);
                    }
                }
                return sb.toString();
            }).orElse("Workflow " + workflowId + " not found.\n");
        } catch (Exception e) {
            log.debug("Could not fetch workflow detail for {}", workflowId, e);
            return "";
        }
    }

    public String formatWorkflowFull(String workflowId) {
        try {
            return workflowRepository.findById(workflowId).map(wf -> {
                var sb = new StringBuilder();
                String projectName = wf.getProject() != null ? wf.getProject().getName() : "—";
                sb.append("**Workflow (full): ").append(wf.getName()).append("** (").append(projectName).append(")\n");
                sb.append("ID: ").append(wf.getId()).append("\n");
                if (wf.getModel() != null && !wf.getModel().isBlank()) {
                    sb.append("Model: ").append(wf.getModel()).append("\n");
                }
                if (wf.getInstructions() != null && !wf.getInstructions().isBlank()) {
                    sb.append("Instructions: ").append(AssistantFormatUtils.truncate(wf.getInstructions().trim(), 600)).append("\n");
                }
                if (wf.getDefaultContext() != null && !wf.getDefaultContext().isEmpty()) {
                    sb.append("Default inputs: ").append(wf.getDefaultContext()).append("\n");
                }
                var blocks = wf.getBlocks();
                if (blocks != null && !blocks.isEmpty()) {
                    sb.append("\nBlocks (").append(blocks.size()).append(", full config):\n");
                    for (int i = 0; i < blocks.size(); i++) {
                        BlockDefinition block = blocks.get(i);
                        Map<String, Object> cfg = block.getConfig() != null ? block.getConfig() : Map.of();
                        sb.append("  ").append(i + 1).append(". type: ").append(block.getType()).append("\n");
                        cfg.forEach((k, v) -> {
                            String valStr = v == null ? "null" : String.valueOf(v);
                            sb.append("       ").append(k).append(": ")
                              .append(AssistantFormatUtils.truncate(valStr, 300)).append("\n");
                        });
                    }
                }
                return sb.toString();
            }).orElse("Workflow '" + workflowId + "' not found.\n");
        } catch (Exception e) {
            log.debug("Could not fetch workflow-full for {}", workflowId, e);
            return "";
        }
    }

    public String formatFindWorkflow(String query) {
        if (query == null || query.isBlank()) {
            return "Search term missing. Expected: <fetch type=\"find_workflow\" id=\"<name-or-substring>\"/>\n";
        }
        try {
            List<com.automation.entity.Workflow> matches = workflowRepository.findByNameContainingIgnoreCase(query.trim());
            if (matches.isEmpty()) {
                return "No workflow found whose name contains '" + query + "'.\n";
            }
            var sb = new StringBuilder();
            sb.append("**Workflow search for '").append(query).append("':** ")
                    .append(matches.size()).append(" matches\n");
            int limit = Math.min(matches.size(), 20);
            for (int i = 0; i < limit; i++) {
                var wf = matches.get(i);
                String projectName = wf.getProject() != null ? wf.getProject().getName() : "—";
                sb.append("  - **").append(wf.getName()).append("** (id: `").append(wf.getId()).append("`, project: ")
                        .append(projectName).append(")\n");
            }
            if (matches.size() > limit) {
                sb.append("  … ").append(matches.size() - limit).append(" more matches (refine the search).\n");
            }
            sb.append("\nUse `<fetch type=\"workflow_detail\" id=\"<id>\"/>` for details.\n");
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not search workflows for '{}'", query, e);
            return "Workflow search failed.\n";
        }
    }

    public String formatWorkflowInputs(String workflowId) {
        try {
            return workflowRepository.findById(workflowId).map(wf -> {
                var sb = new StringBuilder();
                sb.append("**Inputs for workflow: ").append(wf.getName()).append("** (id: ").append(wf.getId()).append(")\n");
                Map<String, Object> ctx = wf.getDefaultContext();
                if (ctx == null || ctx.isEmpty()) {
                    sb.append("(no default inputs defined)\n");
                } else {
                    ctx.forEach((k, v) -> {
                        String valStr = v == null ? "null" : String.valueOf(v);
                        sb.append("  - ").append(k).append(" = ")
                          .append(AssistantFormatUtils.truncate(valStr, 100)).append("\n");
                    });
                }
                return sb.toString();
            }).orElse("Workflow '" + workflowId + "' not found.\n");
        } catch (Exception e) {
            log.debug("Could not fetch workflow-inputs for {}", workflowId, e);
            return "";
        }
    }
}
