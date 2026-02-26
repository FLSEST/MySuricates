/**
 * Variable mappings and metadata for all 16 testable templates.
 * Variables reference files/paths in /tmp/autopilot-testbed/.
 */

export interface TemplateTestConfig {
  /** Template ID as defined in BuiltInTemplates.java */
  templateId: string;
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
    variables: {},
    tier: 'shell',
  },
  {
    templateId: 'folder-backup',
    variables: {},
    tier: 'shell',
  },
  {
    templateId: 'directory-sync',
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
    variables: {
      filepath: 'src/index.ts',
    },
    tier: 'simple-ai',
  },
  {
    templateId: 'iterative-refine',
    variables: {
      filepath: 'README.md',
    },
    tier: 'simple-ai',
  },
  {
    templateId: 'project-overview',
    variables: {},
    tier: 'simple-ai',
  },
  {
    templateId: 'fact-check-pipeline',
    variables: {
      filepath: 'document.md',
    },
    tier: 'simple-ai',
  },
  {
    templateId: 'research-enhance',
    variables: {},
    tier: 'simple-ai',
  },
  {
    templateId: 'code-refactor',
    variables: {
      filepath: 'src/utils.ts',
    },
    tier: 'simple-ai',
  },
  {
    templateId: 'topic-deep-dive',
    variables: {
      topic: 'TypeScript type safety best practices',
    },
    tier: 'simple-ai',
  },
  {
    templateId: 'translation-pipeline',
    variables: {
      filepath: 'README.md',
      target_language: 'English',
    },
    tier: 'simple-ai',
  },
  {
    templateId: 'document-review',
    variables: {
      document: 'document.md',
    },
    tier: 'simple-ai',
  },
  {
    templateId: 'git-to-changelog',
    variables: {
      since_tag: 'v1.0.0',
      new_version: '1.1.0',
      product_name: 'TaskTracker',
    },
    tier: 'simple-ai',
  },
  {
    templateId: 'log-triage',
    variables: {},
    tier: 'simple-ai',
  },
  {
    templateId: 'data-analysis',
    variables: {
      dataset: 'data.csv',
    },
    tier: 'simple-ai',
  },
];

export const allTemplates = [...shellTemplates, ...simpleAiTemplates];
