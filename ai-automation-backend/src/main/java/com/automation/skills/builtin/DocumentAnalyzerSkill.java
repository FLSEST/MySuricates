package com.automation.skills.builtin;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.skills.SkillProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DocumentAnalyzerSkill implements SkillProvider {

    @Override
    public Skill provide() {
        Skill skill = Skill.builder()
                .id("document-analyzer")
                .name("Document Analyzer")
                .description("Analyze documents for quality, grammar, facts, and structure")
                .icon("file-text")
                .category(SkillCategory.DOCUMENT)
                .builtin(true)
                .version("1.0.0")
                .aiContext("""
                        You are an expert document analyzer. You have the following capabilities:
                        - OCR: Extract text from images and scanned documents
                        - Fact Checking: Verify facts against known sources and flag unverifiable claims
                        - Grammar Check: Check grammar, spelling, and style for clarity and correctness
                        - Summarize: Create concise summaries maintaining key points and structure

                        When using these capabilities, be thorough and provide structured output.
                        """)
                .build();

        skill.setCapabilities(List.of(
                buildCapability(skill, "ocr", "Extract text from images",
                        "com.automation.skills.handlers.document.OCRHandler",
                        Map.of("image", "string"),
                        Map.of("text", "string")),
                buildCapability(skill, "fact_check", "Verify facts against sources",
                        "com.automation.skills.handlers.document.FactCheckHandler",
                        Map.of("text", "string"),
                        Map.of("results", "array")),
                buildCapability(skill, "grammar_check", "Check grammar and spelling",
                        "com.automation.skills.handlers.document.GrammarCheckHandler",
                        Map.of("text", "string"),
                        Map.of("corrections", "array")),
                buildCapability(skill, "summarize", "Create document summaries",
                        null,
                        Map.of("text", "string", "length", "integer"),
                        Map.of("summary", "string"))
        ));

        return skill;
    }

    private Capability buildCapability(Skill skill, String name, String description,
                                        String handlerClass,
                                        Map<String, Object> inputSchema,
                                        Map<String, Object> outputSchema) {
        return Capability.builder()
                .skill(skill)
                .name(name)
                .description(description)
                .handlerClass(handlerClass)
                .inputSchema(inputSchema)
                .outputSchema(outputSchema)
                .build();
    }
}
