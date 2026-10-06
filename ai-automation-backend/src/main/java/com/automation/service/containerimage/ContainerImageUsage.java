package com.automation.service.containerimage;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which workflow block runs in which container image (TODO 244e5de5). Reads the definitions only:
 * every {@code container_image} anywhere in a block's config counts, also inside foreach/retry/switch
 * and other wrappers. A block with {@code container_args} runs the image in tool mode (its own
 * entrypoint), otherwise a shell command runs inside it.
 */
public final class ContainerImageUsage {

    public enum Mode { COMMAND, TOOL }

    /** One use: {@code block} is the top-level block's name (or label), nested names follow after " › ". */
    public record WorkflowUse(String workflowId, String workflowName, String block, Mode mode) {}

    private ContainerImageUsage() {
    }

    /** Image reference (normalized, see {@link AgentImageCatalog#normalize}) → uses, sorted by image. */
    public static Map<String, List<WorkflowUse>> scan(Collection<Workflow> workflows) {
        Map<String, List<WorkflowUse>> byImage = new TreeMap<>();
        for (Workflow workflow : workflows) {
            List<BlockDefinition> blocks = workflow.getBlocks();
            if (blocks == null) continue;
            for (int i = 0; i < blocks.size(); i++) {
                BlockDefinition block = blocks.get(i);
                String label = blockLabel(block.getName(), block.getConfig(), i);
                walk(block.getConfig(), label, workflow, byImage);
            }
        }
        return byImage;
    }

    private static void walk(Object node, String label, Workflow workflow, Map<String, List<WorkflowUse>> byImage) {
        if (node instanceof Map<?, ?> map) {
            if (map.get("container_image") instanceof String image && !image.isBlank()) {
                Mode mode = map.get("container_args") instanceof List<?> args && !args.isEmpty() ? Mode.TOOL : Mode.COMMAND;
                byImage.computeIfAbsent(AgentImageCatalog.normalize(image), k -> new ArrayList<>())
                        .add(new WorkflowUse(workflow.getId(), workflow.getName(), label, mode));
            }
            map.values().forEach(value -> descend(value, label, workflow, byImage));
        } else if (node instanceof List<?> list) {
            list.forEach(item -> descend(item, label, workflow, byImage));
        }
    }

    /** A nested block (a map with its own type and config) is named after its parent: "outer › inner". */
    private static void descend(Object value, String label, Workflow workflow, Map<String, List<WorkflowUse>> byImage) {
        if (value instanceof Map<?, ?> child && child.get("type") instanceof String
                && child.get("config") instanceof Map<?, ?> childConfig) {
            String childName = child.get("name") instanceof String n && !n.isBlank() ? n : null;
            walk(childConfig, childName == null ? label : label + " › " + childName, workflow, byImage);
        } else {
            walk(value, label, workflow, byImage);
        }
    }

    private static String blockLabel(String name, Map<String, Object> config, int index) {
        if (name != null && !name.isBlank()) return name;
        if (config != null && config.get("label") instanceof String label && !label.isBlank()) return label;
        return "#" + (index + 1);
    }
}
