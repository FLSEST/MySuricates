package com.automation.config;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Static definitions of all built-in skills.
 * These are seeded into the database on first startup.
 */
public final class BuiltInSkills {

    private BuiltInSkills() {}

    public static List<Skill> getAll() {
        List<Skill> skills = new ArrayList<>();

        // ── CODE ──────────────────────────────────

        skills.add(skill("builtin-code-review", "Code Review", "🔍",
                "Automated code review that identifies bugs, style issues, and suggests improvements.",
                SkillCategory.CODE,
                """
                You are a senior code reviewer. Analyze the provided code for:
                - Bugs and logic errors
                - Security vulnerabilities (SQL injection, XSS, etc.)
                - Performance issues and inefficiencies
                - Code style and readability
                - Adherence to best practices and design patterns

                Provide specific, actionable feedback with line references. Categorize issues by severity (critical, warning, info). Suggest concrete fixes with code examples where appropriate.""",
                List.of(
                        cap("review_code", "Perform a comprehensive code review on the provided source code",
                                null,
                                Map.of("type", "object", "properties", Map.of("code", Map.of("type", "string"), "language", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("issues", Map.of("type", "array"), "summary", Map.of("type", "string")))),
                        cap("suggest_fixes", "Generate fix suggestions for identified code issues",
                                null,
                                Map.of("type", "object", "properties", Map.of("code", Map.of("type", "string"), "issues", Map.of("type", "array"))),
                                Map.of("type", "object", "properties", Map.of("fixes", Map.of("type", "array")))),
                        cap("lint", "Analyze code for style issues, anti-patterns, and best practice violations",
                                "com.automation.skills.handlers.code.LintHandler",
                                Map.of("code", "string", "language", "string"),
                                Map.of("issues", "array")),
                        cap("security_scan", "Identify security vulnerabilities (OWASP Top 10, injection, XSS)",
                                "com.automation.skills.handlers.code.SecurityScanHandler",
                                Map.of("code", "string"),
                                Map.of("vulnerabilities", "array"))
                )));

        skills.add(skill("builtin-test-generator", "Test Generator", "🧪",
                "Automatically generates unit and integration tests for your code.",
                SkillCategory.CODE,
                """
                You are a testing expert. Generate comprehensive tests for the provided code:
                - Write unit tests covering all functions and edge cases
                - Include positive, negative, and boundary test cases
                - Generate integration tests for component interactions
                - Use appropriate mocking strategies for external dependencies
                - Follow the testing framework conventions of the target language (JUnit, pytest, Jest, etc.)

                Output well-structured, runnable test code with clear test names describing the expected behavior.""",
                List.of(
                        cap("generate_unit_tests", "Generate unit tests for the provided code",
                                null,
                                Map.of("type", "object", "properties", Map.of("code", Map.of("type", "string"), "framework", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("tests", Map.of("type", "string")))),
                        cap("generate_integration_tests", "Generate integration tests for component interactions",
                                null,
                                Map.of("type", "object", "properties", Map.of("components", Map.of("type", "array"))),
                                Map.of("type", "object", "properties", Map.of("tests", Map.of("type", "string"))))
                )));

        skills.add(skill("builtin-security-analyzer", "Security Analyzer", "🛡️",
                "Scans code and dependencies for security vulnerabilities and leaked secrets.",
                SkillCategory.CODE,
                """
                You are a security analyst. Perform thorough security analysis:
                - Scan for OWASP Top 10 vulnerabilities
                - Detect hardcoded secrets, API keys, and credentials
                - Audit dependency versions for known CVEs
                - Check for insecure configurations
                - Identify authentication and authorization weaknesses

                Report findings with severity levels (critical, high, medium, low), CVSS scores where applicable, and remediation steps.""",
                List.of(
                        cap("scan_vulnerabilities", "Scan source code for security vulnerabilities",
                                null,
                                Map.of("type", "object", "properties", Map.of("code", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("vulnerabilities", Map.of("type", "array")))),
                        cap("check_secrets", "Detect hardcoded secrets and credentials in code",
                                null,
                                Map.of("type", "object", "properties", Map.of("code", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("secrets", Map.of("type", "array")))),
                        cap("audit_dependencies", "Audit project dependencies for known vulnerabilities",
                                null,
                                Map.of("type", "object", "properties", Map.of("dependencies", Map.of("type", "object"))),
                                Map.of("type", "object", "properties", Map.of("advisories", Map.of("type", "array"))))
                )));

        // ── DOCUMENT ──────────────────────────────

        skills.add(skill("builtin-grammar-style", "Grammar & Style Editor", "✏️",
                "Checks grammar, spelling, and writing style for professional documents.",
                SkillCategory.DOCUMENT,
                """
                You are a professional editor. Review text for:
                - Grammar and spelling errors
                - Punctuation and sentence structure
                - Writing style consistency and clarity
                - Readability (Flesch-Kincaid score estimation)
                - Tone appropriateness for the target audience

                Provide corrections with explanations. Suggest alternative phrasings for unclear passages. Rate overall readability on a 1-10 scale.""",
                List.of(
                        cap("check_grammar", "Check text for grammar, spelling, and punctuation errors",
                                "com.automation.skills.handlers.document.GrammarCheckHandler",
                                Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string"), "language", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("corrections", Map.of("type", "array")))),
                        cap("improve_style", "Suggest style improvements for better readability",
                                null,
                                Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("suggestions", Map.of("type", "array"), "readabilityScore", Map.of("type", "number"))))
                )));

        skills.add(skill("builtin-document-translator", "Document Translator", "🌍",
                "Translates documents between languages while preserving formatting and context.",
                SkillCategory.DOCUMENT,
                """
                You are a professional translator. Translate text accurately while:
                - Preserving the original meaning and nuance
                - Maintaining formatting (markdown, HTML, etc.)
                - Adapting idioms and cultural references appropriately
                - Keeping technical terminology consistent
                - Detecting the source language automatically when not specified

                Provide natural, fluent translations that read as if originally written in the target language.""",
                List.of(
                        cap("translate_text", "Translate text from one language to another",
                                null,
                                Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string"), "targetLanguage", Map.of("type", "string"), "sourceLanguage", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("translation", Map.of("type", "string"), "detectedLanguage", Map.of("type", "string")))),
                        cap("detect_language", "Detect the language of the provided text",
                                null,
                                Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("language", Map.of("type", "string"), "confidence", Map.of("type", "number"))))
                )));

        skills.add(skill("builtin-smart-summarizer", "Smart Summarizer", "📋",
                "Creates concise summaries, extracts key points, and generates outlines.",
                SkillCategory.DOCUMENT,
                """
                You are a summarization expert. Process text to:
                - Create concise summaries at configurable detail levels (brief, standard, detailed)
                - Extract key points and main arguments
                - Generate structured outlines with hierarchy
                - Identify action items and decisions
                - Preserve critical data points and statistics

                Output structured, scannable content that captures the essence of the original text.""",
                List.of(
                        cap("summarize_text", "Create a concise summary of the provided text",
                                null,
                                Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string"), "level", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("summary", Map.of("type", "string")))),
                        cap("extract_key_points", "Extract key points and main arguments from text",
                                null,
                                Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("keyPoints", Map.of("type", "array")))),
                        cap("create_outline", "Generate a structured outline from text content",
                                null,
                                Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("outline", Map.of("type", "array"))))
                )));

        skills.add(skill("builtin-document-analyzer", "Document Analyzer", "📄",
                "Analyze documents for quality, grammar, facts, and structure.",
                SkillCategory.DOCUMENT,
                """
                You are an expert document analyzer. You have the following capabilities:
                - OCR: Extract text from images and scanned documents
                - Fact Checking: Verify facts against known sources and flag unverifiable claims
                - Grammar Check: Check grammar, spelling, and style for clarity and correctness
                - Summarize: Create concise summaries maintaining key points and structure

                When using these capabilities, be thorough and provide structured output.""",
                List.of(
                        cap("ocr", "Extract text from images",
                                "com.automation.skills.handlers.document.OCRHandler",
                                Map.of("image", "string"),
                                Map.of("text", "string")),
                        cap("fact_check", "Verify facts against sources",
                                "com.automation.skills.handlers.document.FactCheckHandler",
                                Map.of("text", "string"),
                                Map.of("results", "array")),
                        cap("grammar_check", "Check grammar and spelling",
                                "com.automation.skills.handlers.document.GrammarCheckHandler",
                                Map.of("text", "string"),
                                Map.of("corrections", "array")),
                        cap("summarize", "Create document summaries",
                                null,
                                Map.of("text", "string", "length", "integer"),
                                Map.of("summary", "string"))
                )));

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

        // ── CONTENT ───────────────────────────────

        skills.add(skill("builtin-blog-writer", "Blog & Article Writer", "📝",
                "Writes engaging blog posts and articles with SEO optimization.",
                SkillCategory.CONTENT,
                """
                You are a content writer. Create compelling articles:
                - Write engaging, well-structured blog posts and articles
                - Optimize for SEO with proper heading hierarchy and keyword placement
                - Create attention-grabbing introductions and conclusions
                - Generate outlines before writing for structured content
                - Suggest compelling headlines and meta descriptions

                Adapt tone and style to the target audience. Use clear, concise language with proper paragraph breaks for readability.""",
                List.of(
                        cap("write_article", "Write a complete blog post or article on a given topic",
                                null,
                                Map.of("type", "object", "properties", Map.of("topic", Map.of("type", "string"), "tone", Map.of("type", "string"), "wordCount", Map.of("type", "number"))),
                                Map.of("type", "object", "properties", Map.of("article", Map.of("type", "string"), "meta", Map.of("type", "object")))),
                        cap("create_article_outline", "Create a structured outline for an article",
                                null,
                                Map.of("type", "object", "properties", Map.of("topic", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("outline", Map.of("type", "array")))),
                        cap("suggest_headlines", "Generate headline suggestions for content",
                                null,
                                Map.of("type", "object", "properties", Map.of("topic", Map.of("type", "string"), "content", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("headlines", Map.of("type", "array"))))
                )));

        skills.add(skill("builtin-email-drafter", "Email Drafter", "📧",
                "Drafts professional emails and suggests improvements for existing ones.",
                SkillCategory.CONTENT,
                """
                You are an email communication expert. Help with emails:
                - Draft professional emails for various contexts (business, follow-up, cold outreach)
                - Improve existing email drafts for clarity and impact
                - Suggest compelling subject lines with good open rates
                - Adapt tone (formal, casual, persuasive) to context
                - Keep emails concise and action-oriented

                Follow email best practices: clear subject, greeting, body, CTA, and sign-off.""",
                List.of(
                        cap("draft_email", "Draft a professional email based on context and purpose",
                                null,
                                Map.of("type", "object", "properties", Map.of("purpose", Map.of("type", "string"), "context", Map.of("type", "string"), "tone", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("subject", Map.of("type", "string"), "body", Map.of("type", "string")))),
                        cap("improve_email", "Improve an existing email draft",
                                null,
                                Map.of("type", "object", "properties", Map.of("email", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("improved", Map.of("type", "string"), "changes", Map.of("type", "array")))),
                        cap("suggest_subject_lines", "Generate effective email subject lines",
                                null,
                                Map.of("type", "object", "properties", Map.of("emailContent", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("subjects", Map.of("type", "array"))))
                )));

        skills.add(skill("builtin-social-media", "Social Media Creator", "📱",
                "Creates platform-optimized social media posts with hashtag suggestions.",
                SkillCategory.CONTENT,
                """
                You are a social media strategist. Create engaging posts:
                - Write platform-optimized content (Twitter/X, LinkedIn, Instagram, Facebook)
                - Suggest relevant and trending hashtags
                - Adapt content format and length for each platform
                - Create attention-grabbing hooks and CTAs
                - Suggest optimal posting times and engagement strategies

                Follow platform-specific best practices for character limits, formatting, and audience engagement.""",
                List.of(
                        cap("create_post", "Create a social media post for a specific platform",
                                null,
                                Map.of("type", "object", "properties", Map.of("topic", Map.of("type", "string"), "platform", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("post", Map.of("type", "string"), "hashtags", Map.of("type", "array")))),
                        cap("suggest_hashtags", "Suggest relevant hashtags for content",
                                null,
                                Map.of("type", "object", "properties", Map.of("content", Map.of("type", "string"), "platform", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("hashtags", Map.of("type", "array")))),
                        cap("adapt_for_platform", "Adapt existing content for a different social media platform",
                                null,
                                Map.of("type", "object", "properties", Map.of("content", Map.of("type", "string"), "targetPlatform", Map.of("type", "string"))),
                                Map.of("type", "object", "properties", Map.of("adapted", Map.of("type", "string"))))
                )));

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

    // ── Helper methods ────────────────────────

    private static Skill skill(String id, String name, String icon, String description,
                                SkillCategory category, String aiContext,
                                List<Capability> capabilities) {
        Skill s = Skill.builder()
                .id(id)
                .name(name)
                .icon(icon)
                .description(description)
                .category(category)
                .aiContext(aiContext)
                .builtin(true)
                .version("1.0.0")
                .build();
        for (Capability c : capabilities) {
            c.setSkill(s);
        }
        s.setCapabilities(new ArrayList<>(capabilities));
        return s;
    }

    private static Capability cap(String name, String description, String handlerClass,
                                   Map<String, Object> inputSchema, Map<String, Object> outputSchema) {
        return cap(name, description, handlerClass, inputSchema, outputSchema, false);
    }

    private static Capability cap(String name, String description, String handlerClass,
                                   Map<String, Object> inputSchema, Map<String, Object> outputSchema,
                                   boolean async) {
        return Capability.builder()
                .name(name)
                .description(description)
                .handlerClass(handlerClass)
                .inputSchema(inputSchema)
                .outputSchema(outputSchema)
                .async(async)
                .build();
    }
}
