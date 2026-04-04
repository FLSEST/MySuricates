package com.automation.service.wish;

import com.automation.entity.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Facade for wish prompt building. Delegates to specialized builders:
 * - {@link WishDecomposePromptBuilder} for decompose prompts
 * - {@link WishEvaluatePromptBuilder} for evaluate prompts
 * - {@link WishPromptHelper} for shared utilities (keyword scoring, model inference)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WishPromptBuilder {

    private final WishDecomposePromptBuilder decomposeBuilder;
    private final WishEvaluatePromptBuilder evaluateBuilder;
    private final WishPromptHelper helper;

    public String buildDecomposePrompt(Wish wish) {
        return decomposeBuilder.buildPrompt(wish);
    }

    public String buildDecomposeSystemPrompt(Wish wish) {
        return decomposeBuilder.buildSystemPrompt(wish);
    }

    @SuppressWarnings("unchecked")
    public String buildEvaluatePrompt(Wish wish) {
        return evaluateBuilder.buildPrompt(wish);
    }

    public String inferModelFromBlocks(List<BlockDefinition> blocks, String description) {
        return helper.inferModelFromBlocks(blocks, description);
    }

    // ── Delegated helper methods (used by tests and external callers) ──

    public String formatBlockPipeline(List<BlockDefinition> blocks) {
        return helper.formatBlockPipeline(blocks);
    }

    public java.util.Set<String> extractKeywords(String text) {
        return helper.extractKeywords(text);
    }

    public int scoreRelevance(java.util.Set<String> keywords, String name, String description, List<BlockDefinition> blocks) {
        return helper.scoreRelevance(keywords, name, description, blocks);
    }
}
