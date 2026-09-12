package com.automation.config;

import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.automation.config.BuiltInSkillSupport.cap;
import static com.automation.config.BuiltInSkillSupport.skill;

final class BuiltInDocumentSkills {

    private BuiltInDocumentSkills() {}

    static List<Skill> all() {
        List<Skill> skills = new ArrayList<>();

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

        return skills;
    }
}
