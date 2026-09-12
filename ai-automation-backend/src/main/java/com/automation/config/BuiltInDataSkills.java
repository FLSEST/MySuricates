package com.automation.config;

import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.automation.config.BuiltInSkillSupport.cap;
import static com.automation.config.BuiltInSkillSupport.skill;

final class BuiltInDataSkills {

    private BuiltInDataSkills() {}

    static List<Skill> all() {
        List<Skill> skills = new ArrayList<>();

        // ── DATA ──────────────────────────────────

        skills.add(skill("builtin-data-analyzer", "Data Analyzer", "📊",
                "Analyzes datasets to detect patterns, anomalies, and generate statistics.",
                SkillCategory.DATA,
                """
                You are a data analyst. Analyze provided data to:
                - Calculate descriptive statistics (mean, median, mode, std dev, percentiles)
                - Detect anomalies and outliers using statistical methods
                - Identify trends, patterns, and correlations
                - Generate data quality reports (missing values, duplicates, type mismatches)
                - Provide visualization recommendations

                Present findings in a structured format with confidence intervals where applicable.""",
                List.of(
                        cap("analyze_dataset", "Perform statistical analysis on a dataset",
                                null,
                                Map.of("type", "object", "properties", Map.of("data", Map.of("type", "string"), "format", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("statistics", Map.of("type", "object"), "insights", Map.of("type", "array")))),
                        cap("detect_anomalies", "Detect anomalies and outliers in data",
                                null,
                                Map.of("type", "object", "properties", Map.of("data", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("anomalies", Map.of("type", "array")))),
                        cap("generate_statistics", "Generate descriptive statistics for data columns",
                                null,
                                Map.of("type", "object", "properties", Map.of("data", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("statistics", Map.of("type", "object"))))
                )));

        skills.add(skill("builtin-report-generator", "Report Generator", "📄",
                "Creates formatted reports and visualization specifications from data.",
                SkillCategory.DATA,
                """
                You are a report generation specialist. Create professional reports:
                - Generate structured reports in markdown format
                - Include executive summaries and detailed analysis sections
                - Create visualization specifications (chart types, axes, colors)
                - Format data tables with appropriate precision
                - Add contextual commentary explaining findings

                Reports should be publication-ready with clear sections, proper formatting, and actionable conclusions.""",
                List.of(
                        cap("generate_report", "Generate a formatted report from analysis results",
                                null,
                                Map.of("type", "object", "properties", Map.of("data", Map.of("type", "object"), "template", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("report", Map.of("type", "string")))),
                        cap("create_visualization_spec", "Create chart/visualization specifications from data",
                                null,
                                Map.of("type", "object", "properties", Map.of("data", Map.of("type", "object"))),
                                Map.of("type", "object", "properties", Map.of("charts", Map.of("type", "array"))))
                )));

        return skills;
    }
}
