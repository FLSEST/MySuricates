package com.automation.service.memory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Extracts structured knowledge from preprocessed workflow execution data.
 */
public interface KnowledgeSummarizer {

    record SummarizationResult(String title, String content, List<String> tags) {

        static final String DEFAULT_TITLE = "Workflow Knowledge";
        static final int MAX_TITLE_LENGTH = 60;

        /**
         * Parses the summarizer output format shared by every CLI provider:
         * {@code TITLE: ...}, {@code TAGS: a, b} and the content after a {@code ---} line.
         * Missing parts fall back to a default title, no tags and the whole response as content.
         */
        public static SummarizationResult parse(String response) {
            String title = DEFAULT_TITLE;
            List<String> tags = List.of();
            String content = response;

            int titleIdx = response.indexOf("TITLE:");
            int tagsIdx = response.indexOf("TAGS:");
            int separatorIdx = response.indexOf("\n---\n");

            if (titleIdx >= 0 && tagsIdx > titleIdx) {
                title = response.substring(titleIdx + 6, tagsIdx).trim();
                if (title.length() > MAX_TITLE_LENGTH) {
                    title = title.substring(0, MAX_TITLE_LENGTH);
                }
            }

            if (tagsIdx >= 0 && separatorIdx > tagsIdx) {
                String tagsLine = response.substring(tagsIdx + 5, separatorIdx).trim();
                tags = Arrays.stream(tagsLine.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
            }

            if (separatorIdx >= 0 && separatorIdx + 5 < response.length()) {
                content = response.substring(separatorIdx + 5).trim();
            }

            return new SummarizationResult(title, content, tags);
        }
    }

    SummarizationResult summarize(String preprocessedInput) throws IOException;
}
