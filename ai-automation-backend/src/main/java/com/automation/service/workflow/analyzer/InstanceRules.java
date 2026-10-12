package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.executor.NestedBlocks;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;

import java.util.List;
import java.util.Map;

/**
 * {@code container-without-docker} (TODO 0ad8853b): a block runs in a container ({@code container_image}),
 * but this instance cannot reach a Docker daemon — the block fails as soon as it runs. Typical for an
 * instance that itself runs in a container, where the socket is off by default. The finding carries the
 * hint of {@link InstanceFacts}, e.g. the switch that mounts the socket.
 */
final class InstanceRules {

    private InstanceRules() {
    }

    static void apply(List<BlockDefinition> blocks, InstanceFacts instance, List<AnalyzerFinding> out) {
        if (instance == null || instance.dockerReachable()) {
            return;
        }
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            int index = i;
            report(image(block.getConfig()), index, block.getName(), "", instance, out);
            NestedBlocks.visit(block.getConfig(), nested -> report(image(nested.config()), index, block.getName(),
                    "Block " + (index + 1) + " › " + nested.path() + ": ", instance, out));
        }
    }

    private static String image(Map<String, Object> config) {
        return config != null && config.get("container_image") instanceof String s && !s.isBlank() ? s : null;
    }

    private static void report(String image, int index, String name, String where, InstanceFacts instance,
                               List<AnalyzerFinding> out) {
        if (image == null) {
            return;
        }
        out.add(AnalyzerFinding.of("container-without-docker", Severity.HIGH, index, name,
                where + "nutzt container_image (" + image + "), diese Instanz erreicht aber keinen Docker-Daemon"
                        + " – der Block scheitert hier. " + instance.dockerHint(),
                where + "uses container_image (" + image + "), but this instance cannot reach a Docker daemon"
                        + " - the block fails here. " + instance.dockerHintEn()));
    }
}
