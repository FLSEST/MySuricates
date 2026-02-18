import type { SkillCategory } from '../types';

export interface CatalogSkill {
  name: string;
  description: string;
  icon: string;
  category: SkillCategory;
  aiContext: string;
  configSchema: Record<string, unknown>;
  version: string;
  capabilities: Array<{
    name: string;
    description: string;
    inputSchema: Record<string, unknown>;
    outputSchema: Record<string, unknown>;
    async: boolean;
  }>;
}

export const skillCatalog: CatalogSkill[] = [
  // ── CODE ──────────────────────────────────
  {
    name: 'Code Review',
    description: 'Automated code review that identifies bugs, style issues, and suggests improvements.',
    icon: '🔍',
    category: 'CODE',
    version: '1.0.0',
    configSchema: {},
    aiContext: `You are a senior code reviewer. Analyze the provided code for:
- Bugs and logic errors
- Security vulnerabilities (SQL injection, XSS, etc.)
- Performance issues and inefficiencies
- Code style and readability
- Adherence to best practices and design patterns

Provide specific, actionable feedback with line references. Categorize issues by severity (critical, warning, info). Suggest concrete fixes with code examples where appropriate.`,
    capabilities: [
      {
        name: 'review_code',
        description: 'Perform a comprehensive code review on the provided source code',
        inputSchema: { type: 'object', properties: { code: { type: 'string' }, language: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { issues: { type: 'array' }, summary: { type: 'string' } } },
        async: false,
      },
      {
        name: 'suggest_fixes',
        description: 'Generate fix suggestions for identified code issues',
        inputSchema: { type: 'object', properties: { code: { type: 'string' }, issues: { type: 'array' } } },
        outputSchema: { type: 'object', properties: { fixes: { type: 'array' } } },
        async: false,
      },
    ],
  },
  {
    name: 'Test Generator',
    description: 'Automatically generates unit and integration tests for your code.',
    icon: '🧪',
    category: 'CODE',
    version: '1.0.0',
    configSchema: {},
    aiContext: `You are a testing expert. Generate comprehensive tests for the provided code:
- Write unit tests covering all functions and edge cases
- Include positive, negative, and boundary test cases
- Generate integration tests for component interactions
- Use appropriate mocking strategies for external dependencies
- Follow the testing framework conventions of the target language (JUnit, pytest, Jest, etc.)

Output well-structured, runnable test code with clear test names describing the expected behavior.`,
    capabilities: [
      {
        name: 'generate_unit_tests',
        description: 'Generate unit tests for the provided code',
        inputSchema: { type: 'object', properties: { code: { type: 'string' }, framework: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { tests: { type: 'string' } } },
        async: false,
      },
      {
        name: 'generate_integration_tests',
        description: 'Generate integration tests for component interactions',
        inputSchema: { type: 'object', properties: { components: { type: 'array' } } },
        outputSchema: { type: 'object', properties: { tests: { type: 'string' } } },
        async: false,
      },
    ],
  },
  {
    name: 'Security Analyzer',
    description: 'Scans code and dependencies for security vulnerabilities and leaked secrets.',
    icon: '🛡️',
    category: 'CODE',
    version: '1.0.0',
    configSchema: {},
    aiContext: `You are a security analyst. Perform thorough security analysis:
- Scan for OWASP Top 10 vulnerabilities
- Detect hardcoded secrets, API keys, and credentials
- Audit dependency versions for known CVEs
- Check for insecure configurations
- Identify authentication and authorization weaknesses

Report findings with severity levels (critical, high, medium, low), CVSS scores where applicable, and remediation steps.`,
    capabilities: [
      {
        name: 'scan_vulnerabilities',
        description: 'Scan source code for security vulnerabilities',
        inputSchema: { type: 'object', properties: { code: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { vulnerabilities: { type: 'array' } } },
        async: false,
      },
      {
        name: 'check_secrets',
        description: 'Detect hardcoded secrets and credentials in code',
        inputSchema: { type: 'object', properties: { code: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { secrets: { type: 'array' } } },
        async: false,
      },
      {
        name: 'audit_dependencies',
        description: 'Audit project dependencies for known vulnerabilities',
        inputSchema: { type: 'object', properties: { dependencies: { type: 'object' } } },
        outputSchema: { type: 'object', properties: { advisories: { type: 'array' } } },
        async: false,
      },
    ],
  },

  // ── DOCUMENT ──────────────────────────────
  {
    name: 'Grammar & Style Editor',
    description: 'Checks grammar, spelling, and writing style for professional documents.',
    icon: '✏️',
    category: 'DOCUMENT',
    version: '1.0.0',
    configSchema: {},
    aiContext: `You are a professional editor. Review text for:
- Grammar and spelling errors
- Punctuation and sentence structure
- Writing style consistency and clarity
- Readability (Flesch-Kincaid score estimation)
- Tone appropriateness for the target audience

Provide corrections with explanations. Suggest alternative phrasings for unclear passages. Rate overall readability on a 1-10 scale.`,
    capabilities: [
      {
        name: 'check_grammar',
        description: 'Check text for grammar, spelling, and punctuation errors',
        inputSchema: { type: 'object', properties: { text: { type: 'string' }, language: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { corrections: { type: 'array' } } },
        async: false,
      },
      {
        name: 'improve_style',
        description: 'Suggest style improvements for better readability',
        inputSchema: { type: 'object', properties: { text: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { suggestions: { type: 'array' }, readabilityScore: { type: 'number' } } },
        async: false,
      },
    ],
  },
  {
    name: 'Document Translator',
    description: 'Translates documents between languages while preserving formatting and context.',
    icon: '🌍',
    category: 'DOCUMENT',
    version: '1.0.0',
    configSchema: {},
    aiContext: `You are a professional translator. Translate text accurately while:
- Preserving the original meaning and nuance
- Maintaining formatting (markdown, HTML, etc.)
- Adapting idioms and cultural references appropriately
- Keeping technical terminology consistent
- Detecting the source language automatically when not specified

Provide natural, fluent translations that read as if originally written in the target language.`,
    capabilities: [
      {
        name: 'translate_text',
        description: 'Translate text from one language to another',
        inputSchema: { type: 'object', properties: { text: { type: 'string' }, targetLanguage: { type: 'string' }, sourceLanguage: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { translation: { type: 'string' }, detectedLanguage: { type: 'string' } } },
        async: false,
      },
      {
        name: 'detect_language',
        description: 'Detect the language of the provided text',
        inputSchema: { type: 'object', properties: { text: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { language: { type: 'string' }, confidence: { type: 'number' } } },
        async: false,
      },
    ],
  },
  {
    name: 'Smart Summarizer',
    description: 'Creates concise summaries, extracts key points, and generates outlines.',
    icon: '📋',
    category: 'DOCUMENT',
    version: '1.0.0',
    configSchema: {},
    aiContext: `You are a summarization expert. Process text to:
- Create concise summaries at configurable detail levels (brief, standard, detailed)
- Extract key points and main arguments
- Generate structured outlines with hierarchy
- Identify action items and decisions
- Preserve critical data points and statistics

Output structured, scannable content that captures the essence of the original text.`,
    capabilities: [
      {
        name: 'summarize_text',
        description: 'Create a concise summary of the provided text',
        inputSchema: { type: 'object', properties: { text: { type: 'string' }, level: { type: 'string', enum: ['brief', 'standard', 'detailed'] } } },
        outputSchema: { type: 'object', properties: { summary: { type: 'string' } } },
        async: false,
      },
      {
        name: 'extract_key_points',
        description: 'Extract key points and main arguments from text',
        inputSchema: { type: 'object', properties: { text: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { keyPoints: { type: 'array' } } },
        async: false,
      },
      {
        name: 'create_outline',
        description: 'Generate a structured outline from text content',
        inputSchema: { type: 'object', properties: { text: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { outline: { type: 'array' } } },
        async: false,
      },
    ],
  },

  // ── DATA ──────────────────────────────────
  {
    name: 'Data Analyzer',
    description: 'Analyzes datasets to detect patterns, anomalies, and generate statistics.',
    icon: '📊',
    category: 'DATA',
    version: '1.0.0',
    configSchema: {},
    aiContext: `You are a data analyst. Analyze provided data to:
- Calculate descriptive statistics (mean, median, mode, std dev, percentiles)
- Detect anomalies and outliers using statistical methods
- Identify trends, patterns, and correlations
- Generate data quality reports (missing values, duplicates, type mismatches)
- Provide visualization recommendations

Present findings in a structured format with confidence intervals where applicable.`,
    capabilities: [
      {
        name: 'analyze_dataset',
        description: 'Perform statistical analysis on a dataset',
        inputSchema: { type: 'object', properties: { data: { type: 'string' }, format: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { statistics: { type: 'object' }, insights: { type: 'array' } } },
        async: false,
      },
      {
        name: 'detect_anomalies',
        description: 'Detect anomalies and outliers in data',
        inputSchema: { type: 'object', properties: { data: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { anomalies: { type: 'array' } } },
        async: false,
      },
      {
        name: 'generate_statistics',
        description: 'Generate descriptive statistics for data columns',
        inputSchema: { type: 'object', properties: { data: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { statistics: { type: 'object' } } },
        async: false,
      },
    ],
  },
  {
    name: 'Report Generator',
    description: 'Creates formatted reports and visualization specifications from data.',
    icon: '📄',
    category: 'DATA',
    version: '1.0.0',
    configSchema: {},
    aiContext: `You are a report generation specialist. Create professional reports:
- Generate structured reports in markdown format
- Include executive summaries and detailed analysis sections
- Create visualization specifications (chart types, axes, colors)
- Format data tables with appropriate precision
- Add contextual commentary explaining findings

Reports should be publication-ready with clear sections, proper formatting, and actionable conclusions.`,
    capabilities: [
      {
        name: 'generate_report',
        description: 'Generate a formatted report from analysis results',
        inputSchema: { type: 'object', properties: { data: { type: 'object' }, template: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { report: { type: 'string' } } },
        async: false,
      },
      {
        name: 'create_visualization_spec',
        description: 'Create chart/visualization specifications from data',
        inputSchema: { type: 'object', properties: { data: { type: 'object' } } },
        outputSchema: { type: 'object', properties: { charts: { type: 'array' } } },
        async: false,
      },
    ],
  },

  // ── CONTENT ───────────────────────────────
  {
    name: 'Blog & Article Writer',
    description: 'Writes engaging blog posts and articles with SEO optimization.',
    icon: '📝',
    category: 'CONTENT',
    version: '1.0.0',
    configSchema: {},
    aiContext: `You are a content writer. Create compelling articles:
- Write engaging, well-structured blog posts and articles
- Optimize for SEO with proper heading hierarchy and keyword placement
- Create attention-grabbing introductions and conclusions
- Generate outlines before writing for structured content
- Suggest compelling headlines and meta descriptions

Adapt tone and style to the target audience. Use clear, concise language with proper paragraph breaks for readability.`,
    capabilities: [
      {
        name: 'write_article',
        description: 'Write a complete blog post or article on a given topic',
        inputSchema: { type: 'object', properties: { topic: { type: 'string' }, tone: { type: 'string' }, wordCount: { type: 'number' } } },
        outputSchema: { type: 'object', properties: { article: { type: 'string' }, meta: { type: 'object' } } },
        async: false,
      },
      {
        name: 'create_outline',
        description: 'Create a structured outline for an article',
        inputSchema: { type: 'object', properties: { topic: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { outline: { type: 'array' } } },
        async: false,
      },
      {
        name: 'suggest_headlines',
        description: 'Generate headline suggestions for content',
        inputSchema: { type: 'object', properties: { topic: { type: 'string' }, content: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { headlines: { type: 'array' } } },
        async: false,
      },
    ],
  },
  {
    name: 'Email Drafter',
    description: 'Drafts professional emails and suggests improvements for existing ones.',
    icon: '📧',
    category: 'CONTENT',
    version: '1.0.0',
    configSchema: {},
    aiContext: `You are an email communication expert. Help with emails:
- Draft professional emails for various contexts (business, follow-up, cold outreach)
- Improve existing email drafts for clarity and impact
- Suggest compelling subject lines with good open rates
- Adapt tone (formal, casual, persuasive) to context
- Keep emails concise and action-oriented

Follow email best practices: clear subject, greeting, body, CTA, and sign-off.`,
    capabilities: [
      {
        name: 'draft_email',
        description: 'Draft a professional email based on context and purpose',
        inputSchema: { type: 'object', properties: { purpose: { type: 'string' }, context: { type: 'string' }, tone: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { subject: { type: 'string' }, body: { type: 'string' } } },
        async: false,
      },
      {
        name: 'improve_email',
        description: 'Improve an existing email draft',
        inputSchema: { type: 'object', properties: { email: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { improved: { type: 'string' }, changes: { type: 'array' } } },
        async: false,
      },
      {
        name: 'suggest_subject_lines',
        description: 'Generate effective email subject lines',
        inputSchema: { type: 'object', properties: { emailContent: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { subjects: { type: 'array' } } },
        async: false,
      },
    ],
  },
  {
    name: 'Social Media Creator',
    description: 'Creates platform-optimized social media posts with hashtag suggestions.',
    icon: '📱',
    category: 'CONTENT',
    version: '1.0.0',
    configSchema: {},
    aiContext: `You are a social media strategist. Create engaging posts:
- Write platform-optimized content (Twitter/X, LinkedIn, Instagram, Facebook)
- Suggest relevant and trending hashtags
- Adapt content format and length for each platform
- Create attention-grabbing hooks and CTAs
- Suggest optimal posting times and engagement strategies

Follow platform-specific best practices for character limits, formatting, and audience engagement.`,
    capabilities: [
      {
        name: 'create_post',
        description: 'Create a social media post for a specific platform',
        inputSchema: { type: 'object', properties: { topic: { type: 'string' }, platform: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { post: { type: 'string' }, hashtags: { type: 'array' } } },
        async: false,
      },
      {
        name: 'suggest_hashtags',
        description: 'Suggest relevant hashtags for content',
        inputSchema: { type: 'object', properties: { content: { type: 'string' }, platform: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { hashtags: { type: 'array' } } },
        async: false,
      },
      {
        name: 'adapt_for_platform',
        description: 'Adapt existing content for a different social media platform',
        inputSchema: { type: 'object', properties: { content: { type: 'string' }, targetPlatform: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { adapted: { type: 'string' } } },
        async: false,
      },
    ],
  },

  // ── RESEARCH ──────────────────────────────
  {
    name: 'Research Assistant',
    description: 'Researches topics, analyzes sources, and identifies knowledge gaps.',
    icon: '🔬',
    category: 'RESEARCH',
    version: '1.0.0',
    configSchema: {},
    aiContext: `You are a research assistant. Help with research tasks:
- Research topics thoroughly with structured findings
- Analyze and evaluate source credibility and relevance
- Identify gaps in current knowledge or coverage
- Synthesize information from multiple sources
- Create annotated bibliographies and reference lists

Present findings in academic-quality format with proper citations and clear methodology.`,
    capabilities: [
      {
        name: 'research_topic',
        description: 'Research a topic and compile structured findings',
        inputSchema: { type: 'object', properties: { topic: { type: 'string' }, depth: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { findings: { type: 'array' }, summary: { type: 'string' } } },
        async: true,
      },
      {
        name: 'analyze_sources',
        description: 'Analyze and evaluate research sources for credibility',
        inputSchema: { type: 'object', properties: { sources: { type: 'array' } } },
        outputSchema: { type: 'object', properties: { evaluations: { type: 'array' } } },
        async: false,
      },
      {
        name: 'identify_gaps',
        description: 'Identify knowledge gaps in research coverage',
        inputSchema: { type: 'object', properties: { research: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { gaps: { type: 'array' } } },
        async: false,
      },
    ],
  },
  {
    name: 'Fact Checker',
    description: 'Verifies claims, checks statistics, and assesses information reliability.',
    icon: '✅',
    category: 'RESEARCH',
    version: '1.0.0',
    configSchema: {},
    aiContext: `You are a fact-checker. Verify information rigorously:
- Verify factual claims against known information
- Check statistics and numerical data for accuracy
- Assess the reliability and bias of sources
- Identify misleading or out-of-context claims
- Rate confidence level for each verification

Provide clear verdicts (confirmed, unconfirmed, false, misleading) with supporting evidence and reasoning.`,
    capabilities: [
      {
        name: 'verify_claims',
        description: 'Verify factual claims in the provided text',
        inputSchema: { type: 'object', properties: { text: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { claims: { type: 'array' } } },
        async: false,
      },
      {
        name: 'check_statistics',
        description: 'Verify statistics and numerical claims',
        inputSchema: { type: 'object', properties: { statistics: { type: 'array' } } },
        outputSchema: { type: 'object', properties: { results: { type: 'array' } } },
        async: false,
      },
      {
        name: 'assess_reliability',
        description: 'Assess the reliability of a source or document',
        inputSchema: { type: 'object', properties: { source: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { score: { type: 'number' }, factors: { type: 'array' } } },
        async: false,
      },
    ],
  },

  // ── BUSINESS ──────────────────────────────
  {
    name: 'Meeting Notes Processor',
    description: 'Processes meeting notes to extract action items, decisions, and summaries.',
    icon: '🗒️',
    category: 'BUSINESS',
    version: '1.0.0',
    configSchema: {},
    aiContext: `You are a meeting notes processor. Extract structured information:
- Create concise meeting summaries
- Extract action items with assignees and deadlines
- Identify key decisions and their rationale
- List open questions and follow-ups
- Organize by topic/agenda item

Output in a clean, scannable format that can be shared with attendees and stakeholders.`,
    capabilities: [
      {
        name: 'process_meeting_notes',
        description: 'Process raw meeting notes into structured format',
        inputSchema: { type: 'object', properties: { notes: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { summary: { type: 'string' }, decisions: { type: 'array' } } },
        async: false,
      },
      {
        name: 'extract_action_items',
        description: 'Extract action items with assignees and deadlines',
        inputSchema: { type: 'object', properties: { notes: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { actionItems: { type: 'array' } } },
        async: false,
      },
    ],
  },
  {
    name: 'Risk Assessor',
    description: 'Identifies risks, creates risk matrices, and suggests mitigations.',
    icon: '⚠️',
    category: 'BUSINESS',
    version: '1.0.0',
    configSchema: {},
    aiContext: `You are a risk assessment specialist. Analyze risks:
- Identify potential risks across categories (technical, financial, operational, legal)
- Assess probability and impact for each risk
- Create risk matrices with prioritized rankings
- Suggest concrete mitigation strategies
- Define risk indicators and monitoring approaches

Present risks in a structured format with clear severity ratings and actionable mitigation plans.`,
    capabilities: [
      {
        name: 'identify_risks',
        description: 'Identify potential risks in a project or scenario',
        inputSchema: { type: 'object', properties: { context: { type: 'string' } } },
        outputSchema: { type: 'object', properties: { risks: { type: 'array' } } },
        async: false,
      },
      {
        name: 'create_risk_matrix',
        description: 'Create a risk matrix with probability and impact ratings',
        inputSchema: { type: 'object', properties: { risks: { type: 'array' } } },
        outputSchema: { type: 'object', properties: { matrix: { type: 'object' } } },
        async: false,
      },
      {
        name: 'suggest_mitigations',
        description: 'Suggest mitigation strategies for identified risks',
        inputSchema: { type: 'object', properties: { risks: { type: 'array' } } },
        outputSchema: { type: 'object', properties: { mitigations: { type: 'array' } } },
        async: false,
      },
    ],
  },
];
