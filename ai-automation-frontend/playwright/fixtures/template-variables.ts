/**
 * Variable mappings and metadata for all 16 testable templates.
 * Variables reference files/paths in /tmp/autopilot-testbed/.
 *
 * Note: Template IDs in the DB may be UUIDs (legacy seeding) rather than slugs.
 * We match templates by their English name (`name.en`) instead.
 */

export interface TemplateTestConfig {
  /** Slug from BuiltInTemplates.java — used for result filenames */
  templateId: string;
  /** English name to look up the template in the API response */
  nameEn: string;
  /** Variables to pass as execution context */
  variables: Record<string, string>;
  /** Which test tier this belongs to */
  tier: 'shell' | 'simple-ai';
  /** Polling timeout override (ms). Defaults to tier default. */
  timeoutMs?: number;
}

const WORKSPACE = '/tmp/autopilot-testbed';

// ── Shell Templates (4) ──

export const shellTemplates: TemplateTestConfig[] = [
  {
    templateId: 'code-quality',
    nameEn: 'Code Quality',
    variables: {},
    tier: 'shell',
  },
  {
    templateId: 'folder-backup',
    nameEn: 'Folder Backup (tar.gz)',
    variables: {},
    tier: 'shell',
  },
  {
    templateId: 'directory-sync',
    nameEn: 'Directory Sync',
    variables: {
      source: `${WORKSPACE}/sync-source/`,
      target: `${WORKSPACE}/sync-target/`,
      exclude_patterns: '*.tmp,*.log',
      delete_removed: 'false',
    },
    tier: 'shell',
  },
  {
    templateId: 'git-statistics-report',
    nameEn: 'Git Statistics Report',
    variables: {
      project_path: WORKSPACE,
      since: '1 year ago',
    },
    tier: 'shell',
  },
];

// ── Simple-AI Templates (12) ──

export const simpleAiTemplates: TemplateTestConfig[] = [
  {
    templateId: 'file-improve',
    nameEn: 'File Analysis & Improvement',
    variables: {
      filepath: 'src/index.ts',
    },
    tier: 'simple-ai',
  },
  {
    templateId: 'iterative-refine',
    nameEn: 'Iterative Refinement',
    variables: {
      filepath: 'README.md',
    },
    tier: 'simple-ai',
  },
  {
    templateId: 'project-overview',
    nameEn: 'Project Overview & Synthesis',
    variables: {},
    tier: 'simple-ai',
  },
  {
    templateId: 'fact-check-pipeline',
    nameEn: 'Fact-Check Pipeline',
    variables: {
      filepath: 'document.md',
    },
    tier: 'simple-ai',
  },
  {
    templateId: 'research-enhance',
    nameEn: 'Research & Enrichment',
    variables: {},
    tier: 'simple-ai',
  },
  {
    templateId: 'code-refactor',
    nameEn: 'Code Analysis & Refactoring',
    variables: {
      filepath: 'src/utils.ts',
    },
    tier: 'simple-ai',
  },
  {
    templateId: 'topic-deep-dive',
    nameEn: 'Topic Deep Dive',
    variables: {
      topic: 'TypeScript type safety best practices',
    },
    tier: 'simple-ai',
  },
  {
    templateId: 'translation-pipeline',
    nameEn: 'Translation Pipeline',
    variables: {
      filepath: 'README.md',
      target_language: 'English',
    },
    tier: 'simple-ai',
  },
  {
    templateId: 'document-review',
    nameEn: 'Document Review',
    variables: {
      document: 'document.md',
    },
    tier: 'simple-ai',
  },
  {
    templateId: 'git-to-changelog',
    nameEn: 'Git History \u2192 Changelog & Release Notes',
    variables: {
      since_tag: 'v1.0.0',
      new_version: '1.1.0',
      product_name: 'TaskTracker',
    },
    tier: 'simple-ai',
  },
  {
    templateId: 'log-triage',
    nameEn: 'Log Triage (Cross-Repo)',
    variables: {},
    tier: 'simple-ai',
  },
  {
    templateId: 'data-analysis',
    nameEn: 'Data Analysis',
    variables: {
      dataset: 'data.csv',
    },
    tier: 'simple-ai',
  },
];

export const allTemplates = [...shellTemplates, ...simpleAiTemplates];
