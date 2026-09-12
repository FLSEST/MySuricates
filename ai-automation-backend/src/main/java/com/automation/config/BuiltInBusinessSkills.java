package com.automation.config;

import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.automation.config.BuiltInSkillSupport.cap;
import static com.automation.config.BuiltInSkillSupport.skill;

final class BuiltInBusinessSkills {

    private BuiltInBusinessSkills() {}

    static List<Skill> all() {
        List<Skill> skills = new ArrayList<>();

        // ── BUSINESS ──────────────────────────────

        skills.add(skill("builtin-meeting-notes", "Meeting Notes Processor", "🗒️",
                "Processes meeting notes to extract action items, decisions, and summaries.",
                SkillCategory.BUSINESS,
                """
                You are a meeting notes processor. Extract structured information:
                - Create concise meeting summaries
                - Extract action items with assignees and deadlines
                - Identify key decisions and their rationale
                - List open questions and follow-ups
                - Organize by topic/agenda item

                Output in a clean, scannable format that can be shared with attendees and stakeholders.""",
                List.of(
                        cap("process_meeting_notes", "Process raw meeting notes into structured format",
                                null,
                                Map.of("type", "object", "properties", Map.of("notes", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("summary", Map.of("type", "string"), "decisions", Map.of("type", "array")))),
                        cap("extract_action_items", "Extract action items with assignees and deadlines",
                                null,
                                Map.of("type", "object", "properties", Map.of("notes", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("actionItems", Map.of("type", "array"))))
                )));

        skills.add(skill("builtin-risk-assessor", "Risk Assessor", "⚠️",
                "Identifies risks, creates risk matrices, and suggests mitigations.",
                SkillCategory.BUSINESS,
                """
                You are a risk assessment specialist. Analyze risks:
                - Identify potential risks across categories (technical, financial, operational, legal)
                - Assess probability and impact for each risk
                - Create risk matrices with prioritized rankings
                - Suggest concrete mitigation strategies
                - Define risk indicators and monitoring approaches

                Present risks in a structured format with clear severity ratings and actionable mitigation plans.""",
                List.of(
                        cap("identify_risks", "Identify potential risks in a project or scenario",
                                null,
                                Map.of("type", "object", "properties", Map.of("context", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("risks", Map.of("type", "array")))),
                        cap("create_risk_matrix", "Create a risk matrix with probability and impact ratings",
                                null,
                                Map.of("type", "object", "properties", Map.of("risks", Map.of("type", "array"))),
                                Map.of("type", "object", "properties", Map.of("matrix", Map.of("type", "object")))),
                        cap("suggest_mitigations", "Suggest mitigation strategies for identified risks",
                                null,
                                Map.of("type", "object", "properties", Map.of("risks", Map.of("type", "array"))),
                                Map.of("type", "object", "properties", Map.of("mitigations", Map.of("type", "array"))))
                )));

        return skills;
    }
}
