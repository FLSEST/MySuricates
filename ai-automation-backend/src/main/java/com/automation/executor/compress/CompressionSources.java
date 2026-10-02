package com.automation.executor.compress;

import com.automation.executor.ExecutionContext;
import com.automation.executor.SourceStep;
import com.automation.executor.SourceStepIndex;

/** Resolves a compression block's single source: a block name or "previous", with the stored step facts. */
public final class CompressionSources {

    /** {@code label} is the header name, {@code origin} the step whose status the header shows, {@code text} the content to compress. */
    public record Resolved(String label, SourceStep origin, String text) {
    }

    private CompressionSources() {
    }

    public static Resolved resolve(ExecutionContext ctx, Object rawSource) {
        if (!(rawSource instanceof String source) || source.isBlank()) {
            throw new CompressionException("'source' is missing — the name of an earlier block or \"previous\"");
        }
        SourceStepIndex index = ctx.getSourceSteps();
        SourceStep step;
        String text;
        if ("previous".equals(source)) {
            step = index.last().orElseThrow(() -> new CompressionException("Source \"previous\": no step has run in this run yet"));
            text = ctx.getPreviousOutput();
        } else {
            if (index.isAmbiguous(source)) {
                throw new CompressionException("Source '" + source + "' is ambiguous — the block name occurs more than once in the workflow");
            }
            step = index.find(source).orElseThrow(() -> new CompressionException("Source '" + source
                    + "' has not run in this run yet (check the block name; the source must come before the compression block)"));
            text = ctx.getBlockOutput(source);
        }
        String own = step.blockName() != null ? step.blockName() : "step " + step.stepIndex();
        String body = text == null ? "" : text;
        if (step.root() != null) {
            SourceStep origin = step.origin();
            String originName = origin.blockName() != null ? origin.blockName() : "step " + origin.stepIndex();
            return new Resolved(originName + " (via " + own + ")", origin, CompressionOutput.bodyOf(body));
        }
        return new Resolved(own, step, body);
    }
}
