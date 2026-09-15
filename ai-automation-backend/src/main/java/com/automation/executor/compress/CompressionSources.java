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
            throw new CompressionException("'source' fehlt — Blockname eines früheren Blocks oder \"previous\"");
        }
        SourceStepIndex index = ctx.getSourceSteps();
        SourceStep step;
        String text;
        if ("previous".equals(source)) {
            step = index.last().orElseThrow(() -> new CompressionException("Quelle \"previous\": in diesem Run lief noch kein Schritt"));
            text = ctx.getPreviousOutput();
        } else {
            if (index.isAmbiguous(source)) {
                throw new CompressionException("Quelle '" + source + "' ist mehrdeutig — der Blockname kommt im Workflow mehrfach vor");
            }
            step = index.find(source).orElseThrow(() -> new CompressionException("Quelle '" + source
                    + "' ist in diesem Run noch nicht gelaufen (Blockname prüfen; die Quelle muss vor dem Komprimierungsblock stehen)"));
            text = ctx.getBlockOutput(source);
        }
        String own = step.blockName() != null ? step.blockName() : "Schritt " + step.stepIndex();
        String body = text == null ? "" : text;
        if (step.root() != null) {
            SourceStep origin = step.origin();
            String originName = origin.blockName() != null ? origin.blockName() : "Schritt " + origin.stepIndex();
            return new Resolved(originName + " (über " + own + ")", origin, CompressionOutput.bodyOf(body));
        }
        return new Resolved(own, step, body);
    }
}
