package com.automation.service.assistant.fetch;

import com.automation.repository.WorkflowRepository;
import com.automation.service.assistant.AssistantFormatUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Renders a one-glance summary of a block's config (prompt/command/condition/…)
 * per block type. Shared by {@link WorkflowFetchHandler} and {@link PoolFetchHandler}
 * so stored workflows and pool templates read identically.
 */
@Component
@RequiredArgsConstructor
public class BlockConfigSummaryFormatter {

    private final WorkflowRepository workflowRepository;

    @SuppressWarnings("unchecked")
    public void appendBlockConfigSummary(StringBuilder sb, String type, Map<String, Object> cfg) {
        switch (type) {
            case "simple", "agentic" -> {
                Object prompt = cfg.get("prompt");
                if (prompt != null) sb.append("     prompt: ").append(AssistantFormatUtils.truncate(String.valueOf(prompt).trim(), 200)).append("\n");
                Object model = cfg.get("model");
                if (model != null && !String.valueOf(model).isBlank()) sb.append("     model: ").append(model).append("\n");
            }
            case "shell" -> {
                Object command = cfg.get("command");
                if (command != null) sb.append("     command: ").append(AssistantFormatUtils.truncate(String.valueOf(command).trim(), 300)).append("\n");
            }
            case "conditional" -> {
                Object condition = cfg.get("condition");
                if (condition != null) sb.append("     condition: ").append(condition).append("\n");
                Object trueBlocks = cfg.get("true_blocks");
                Object falseBlocks = cfg.get("false_blocks");
                int trueCount  = trueBlocks  instanceof List<?> l ? l.size() : 0;
                int falseCount = falseBlocks instanceof List<?> l ? l.size() : 0;
                sb.append("     if true: ").append(trueCount).append(" block(s), if false: ").append(falseCount).append(" block(s)\n");
            }
            case "foreach" -> {
                Object items = cfg.get("items");
                if (items != null) sb.append("     items: ").append(items).append("\n");
                Object nested = cfg.get("blocks");
                int count = nested instanceof List<?> l ? l.size() : 0;
                sb.append("     nested blocks: ").append(count).append("\n");
            }
            case "switch" -> {
                Object cases = cfg.get("cases");
                int count = cases instanceof List<?> l ? l.size() : 0;
                sb.append("     cases: ").append(count).append("\n");
            }
            case "approval" -> {
                Object question = cfg.get("question");
                if (question != null) sb.append("     question: ").append(question).append("\n");
            }
            case "retry" -> {
                Object maxAttempts = cfg.get("max_attempts");
                if (maxAttempts != null) sb.append("     max_attempts: ").append(maxAttempts).append("\n");
                Object innerBlock = cfg.get("block");
                if (innerBlock instanceof Map<?, ?> inner) {
                    sb.append("     inner block type: ").append(inner.get("type")).append("\n");
                }
            }
            case "sub-workflow" -> {
                Object wfId = cfg.get("workflow_id");
                if (wfId != null) {
                    String name = workflowRepository.findById(String.valueOf(wfId))
                            .map(w -> w.getName()).orElse(String.valueOf(wfId));
                    sb.append("     sub-workflow: ").append(name).append("\n");
                }
            }
            case "human-input" -> {
                Object prompt = cfg.get("prompt");
                if (prompt != null) sb.append("     prompt: ").append(prompt).append("\n");
                Object inputType = cfg.get("input_type");
                if (inputType != null) sb.append("     input_type: ").append(inputType).append("\n");
            }
            case "compress", "compress-ai" -> {
                Object source = cfg.get("source");
                if (source != null) sb.append("     source: ").append(source).append("\n");
                if (cfg.get("stages") instanceof List<?> stages) sb.append("     stages: ").append(stages.size()).append("\n");
                Object instruction = cfg.get("instruction");
                if (instruction != null) sb.append("     instruction: ").append(AssistantFormatUtils.truncate(String.valueOf(instruction).trim(), 200)).append("\n");
            }
            default -> {
                cfg.forEach((k, v) -> {
                    if (v != null && !String.valueOf(v).isBlank()) {
                        sb.append("     ").append(k).append(": ").append(AssistantFormatUtils.truncate(String.valueOf(v), 80)).append("\n");
                    }
                });
            }
        }
    }
}
