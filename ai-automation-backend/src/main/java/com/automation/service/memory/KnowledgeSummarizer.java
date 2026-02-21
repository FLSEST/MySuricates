package com.automation.service.memory;

import java.io.IOException;
import java.util.List;

/**
 * Extracts structured knowledge from preprocessed workflow execution data.
 */
public interface KnowledgeSummarizer {

    record SummarizationResult(String title, String content, List<String> tags) {}

    SummarizationResult summarize(String preprocessedInput) throws IOException;
}
