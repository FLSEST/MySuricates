package com.automation.config;

import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.automation.config.BuiltInSkillSupport.cap;
import static com.automation.config.BuiltInSkillSupport.skill;

final class BuiltInContentSkills {

    private BuiltInContentSkills() {}

    static List<Skill> all() {
        List<Skill> skills = new ArrayList<>();

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

        return skills;
    }
}
