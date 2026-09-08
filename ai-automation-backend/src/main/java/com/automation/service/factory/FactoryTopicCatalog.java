package com.automation.service.factory;

import java.util.List;
import java.util.Map;

/**
 * The fixed topic categories offered by the workflow factory UI.
 *
 * <p>Extracted from {@code WorkflowFactoryService} (Audit-TODO 3b0a021c). Pure constant.
 */
public final class FactoryTopicCatalog {

    private FactoryTopicCatalog() {
    }

    private static final List<Map<String, String>> CATEGORIES = List.of(
            Map.of("id", "CODE", "name", "Code", "description", "Lint, Test, Review, Refactor, Deploy"),
            Map.of("id", "CONTENT", "name", "Content", "description", "Blog, Docs, Translation, Email, Social"),
            Map.of("id", "DATA", "name", "Data", "description", "CSV → Report, Analyse, Pipeline, ETL"),
            Map.of("id", "RESEARCH", "name", "Research", "description", "Topic Summary, Fact-Check, Compare"),
            Map.of("id", "TRANSFORM", "name", "Transform", "description", "Format, Convert, Migrate, Merge"),
            Map.of("id", "DEVOPS", "name", "DevOps", "description", "CI/CD, Monitor, Backup, Update"),
            Map.of("id", "CREATIVE", "name", "Creative", "description", "Design, Prototype, Generate, Iterate"),
            Map.of("id", "CUSTOM", "name", "Custom", "description", "Free-form description")
    );

    /** Returns available topic categories. */
    public static List<Map<String, String>> getTopicCategories() {
        return CATEGORIES;
    }
}
