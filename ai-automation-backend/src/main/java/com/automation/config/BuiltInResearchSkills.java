package com.automation.config;

import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.automation.config.BuiltInSkillSupport.cap;
import static com.automation.config.BuiltInSkillSupport.skill;

final class BuiltInResearchSkills {

    private BuiltInResearchSkills() {}

    static List<Skill> all() {
        List<Skill> skills = new ArrayList<>();

        // ── RESEARCH ──────────────────────────────

        skills.add(skill("builtin-research-assistant", "Research Assistant", "🔬",
                "Researches topics, analyzes sources, and identifies knowledge gaps.",
                SkillCategory.RESEARCH,
                """
                You are a research assistant. Help with research tasks:
                - Research topics thoroughly with structured findings
                - Analyze and evaluate source credibility and relevance
                - Identify gaps in current knowledge or coverage
                - Synthesize information from multiple sources
                - Create annotated bibliographies and reference lists

                Present findings in academic-quality format with proper citations and clear methodology.""",
                List.of(
                        cap("research_topic", "Research a topic and compile structured findings",
                                null,
                                Map.of("type", "object", "properties", Map.of("topic", Map.of("type", "string"), "depth", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("findings", Map.of("type", "array"), "summary", Map.of("type", "string"))),
                                true),
                        cap("analyze_sources", "Analyze and evaluate research sources for credibility",
                                null,
                                Map.of("type", "object", "properties", Map.of("sources", Map.of("type", "array"))),
                                Map.of("type", "object", "properties", Map.of("evaluations", Map.of("type", "array")))),
                        cap("identify_gaps", "Identify knowledge gaps in research coverage",
                                null,
                                Map.of("type", "object", "properties", Map.of("research", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("gaps", Map.of("type", "array"))))
                )));

        skills.add(skill("builtin-fact-checker", "Fact Checker", "✅",
                "Verifies claims, checks statistics, and assesses information reliability.",
                SkillCategory.RESEARCH,
                """
                You are a fact-checker. Verify information rigorously:
                - Verify factual claims against known information
                - Check statistics and numerical data for accuracy
                - Assess the reliability and bias of sources
                - Identify misleading or out-of-context claims
                - Rate confidence level for each verification

                Provide clear verdicts (confirmed, unconfirmed, false, misleading) with supporting evidence and reasoning.""",
                List.of(
                        cap("verify_claims", "Verify factual claims in the provided text",
                                null,
                                Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("claims", Map.of("type", "array")))),
                        cap("check_statistics", "Verify statistics and numerical claims",
                                null,
                                Map.of("type", "object", "properties", Map.of("statistics", Map.of("type", "array"))),
                                Map.of("type", "object", "properties", Map.of("results", Map.of("type", "array")))),
                        cap("assess_reliability", "Assess the reliability of a source or document",
                                null,
                                Map.of("type", "object", "properties", Map.of("source", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("score", Map.of("type", "number"), "factors", Map.of("type", "array"))))
                )));

        return skills;
    }
}
