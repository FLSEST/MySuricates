package com.automation.skills.builtin;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.skills.SkillProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DataProcessorSkill implements SkillProvider {

    @Override
    public Skill provide() {
        Skill skill = Skill.builder()
                .id("data-processor")
                .name("Data Processor")
                .description("Process, analyze, and visualize data")
                .icon("bar-chart")
                .category(SkillCategory.DATA)
                .builtin(true)
                .version("1.0.0")
                .aiContext("""
                        You are an expert data analyst. You have the following capabilities:
                        - Analyze Statistics: Compute statistical metrics (mean, median, std, correlations)
                        - Detect Anomalies: Identify outliers and anomalous data points
                        - Generate Visualizations: Suggest and describe chart types for data

                        Provide data-driven insights with supporting evidence.
                        """)
                .build();

        skill.setCapabilities(List.of(
                Capability.builder()
                        .skill(skill)
                        .name("analyze_statistics")
                        .description("Compute statistical metrics")
                        .inputSchema(Map.of("data", "string", "metrics", "array"))
                        .outputSchema(Map.of("results", "object"))
                        .build(),
                Capability.builder()
                        .skill(skill)
                        .name("detect_anomalies")
                        .description("Identify outliers in data")
                        .inputSchema(Map.of("data", "string"))
                        .outputSchema(Map.of("anomalies", "array"))
                        .build(),
                Capability.builder()
                        .skill(skill)
                        .name("generate_visualizations")
                        .description("Suggest visualizations for data")
                        .inputSchema(Map.of("data", "string", "chart_types", "array"))
                        .outputSchema(Map.of("charts", "array"))
                        .build()
        ));

        return skill;
    }
}
