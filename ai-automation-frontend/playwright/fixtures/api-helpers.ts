/**
 * REST API helpers for Playwright template tests.
 * All calls go to http://localhost:9090/api/v1 (bypassing the Vite proxy).
 */

import { execSync } from 'child_process';
import { existsSync, mkdirSync, cpSync, writeFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const API = 'http://localhost:9090/api/v1';
export const TEST_PREFIX = 'PW-Template';

// Auto-detect API key
let apiKey = '';
try {
  const __dir = dirname(fileURLToPath(import.meta.url));
  const dbPath = resolve(__dir, '../../../ai-automation-backend/data/automation.db');
  if (existsSync(dbPath)) {
    const script = `import sqlite3,sys;c=sqlite3.connect(sys.argv[1]);print(c.execute("SELECT value FROM app_settings WHERE key=?",("api-key",)).fetchone()[0])`;
    apiKey = execSync(`python3 -c '${script}' '${dbPath}'`, {
      encoding: 'utf-8',
      timeout: 5000,
    }).trim();
  }
} catch {
  // no key
}

export function getApiKey(): string {
  return apiKey;
}

function headers(): Record<string, string> {
  const h: Record<string, string> = { 'Content-Type': 'application/json' };
  if (apiKey) h['X-API-Key'] = apiKey;
  return h;
}

async function request(method: string, path: string, body?: unknown): Promise<unknown> {
  const res = await fetch(`${API}${path}`, {
    method,
    headers: headers(),
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`API ${method} ${path} failed (${res.status}): ${text}`);
  }
  if (res.status === 204) return null;
  return res.json();
}

// ── Projects ──

export interface ProjectResponse {
  id: string;
  name: string;
  path: string;
}

export async function createProject(
  name: string,
  path = '/tmp/autopilot-testbed',
): Promise<ProjectResponse> {
  return (await request('POST', '/projects', {
    name,
    type: 'CODE',
    path,
  })) as ProjectResponse;
}

// ── Templates ──

export interface TemplateResponse {
  id: string;
  name: Record<string, string>;
  description: Record<string, string>;
  icon: string;
  category: string | null;
  blocks: Array<{ type: string; config: Record<string, unknown> }>;
  instructions: string | null;
  model: string | null;
  builtIn: boolean;
  qualityStatus: string;
}

export async function getTemplates(): Promise<TemplateResponse[]> {
  return (await request('GET', '/workflow-templates')) as TemplateResponse[];
}

// ── Workflows ──

export interface WorkflowResponse {
  id: string;
  name: string;
  projectId: string;
  blocks: Array<{ type: string; config: Record<string, unknown> }>;
}

export interface CreateWorkflowRequest {
  projectId: string;
  name: string;
  description?: string;
  icon?: string;
  blocks?: Array<{ type: string; config: Record<string, unknown> }>;
  instructions?: string;
  model?: string;
  templateId?: string;
}

export async function createWorkflow(req: CreateWorkflowRequest): Promise<WorkflowResponse> {
  return (await request('POST', '/workflows', req)) as WorkflowResponse;
}

// ── Execution ──

export interface RunResponse {
  id: string;
  workflowId: string;
  workflowName: string;
  status: string;
  steps: Array<{
    id: string;
    stepIndex: number;
    blockType: string;
    input: string;
    output: string;
    status: string;
    durationMs: number | null;
    error: string | null;
  }>;
  startedAt: string;
  completedAt: string | null;
  durationMs: number | null;
  error: Record<string, unknown> | null;
  metadata: Record<string, unknown>;
  inputContext?: Record<string, unknown>;
}

export async function executeWorkflow(
  workflowId: string,
  context?: Record<string, string>,
): Promise<RunResponse> {
  return (await request('POST', `/workflows/${workflowId}/execute`, {
    context: context ?? {},
  })) as RunResponse;
}

export async function getWorkflowRun(
  workflowId: string,
  runId: string,
): Promise<RunResponse> {
  return (await request('GET', `/workflows/${workflowId}/runs/${runId}`)) as RunResponse;
}

/**
 * Poll a workflow run until it reaches a terminal state (COMPLETED/FAILED/CANCELLED).
 */
export async function pollRunCompletion(
  workflowId: string,
  runId: string,
  opts?: { intervalMs?: number; timeoutMs?: number },
): Promise<RunResponse> {
  const interval = opts?.intervalMs ?? 3000;
  const timeout = opts?.timeoutMs ?? 10 * 60 * 1000; // 10 min default
  const deadline = Date.now() + timeout;

  while (Date.now() < deadline) {
    const run = await getWorkflowRun(workflowId, runId);
    if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(run.status)) {
      return run;
    }
    await new Promise((r) => setTimeout(r, interval));
  }

  // Final check
  const finalRun = await getWorkflowRun(workflowId, runId);
  if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(finalRun.status)) {
    return finalRun;
  }
  throw new Error(`Run ${runId} did not complete within ${timeout / 1000}s (status: ${finalRun.status})`);
}

// ── Artifacts ──

/**
 * Capture artifacts generated by a template run.
 * Saves git diff and known output paths into results directory.
 */
export function captureArtifacts(
  templateId: string,
  workspacePath: string,
  resultsDir: string,
): void {
  const artifactsDir = resolve(resultsDir, templateId, 'artifacts');
  mkdirSync(artifactsDir, { recursive: true });

  // Save git diff
  try {
    const diff = execSync('git diff', {
      cwd: workspacePath,
      encoding: 'utf-8',
      timeout: 10000,
    });
    if (diff.trim()) {
      writeFileSync(resolve(artifactsDir, 'changes.diff'), diff);
    }
  } catch {
    // no git changes
  }

  // Save untracked files info
  try {
    const status = execSync('git status --porcelain', {
      cwd: workspacePath,
      encoding: 'utf-8',
      timeout: 10000,
    });
    if (status.trim()) {
      writeFileSync(resolve(artifactsDir, 'git-status.txt'), status);
    }
  } catch {
    // ignore
  }

  // Copy known output files/directories per template
  const outputPaths: Record<string, string[]> = {
    'folder-backup': ['backup'],
    'directory-sync': ['sync-target'],
    'git-to-changelog': ['CHANGELOG.md', 'RELEASE_NOTES.md', 'MIGRATION.md'],
    'code-refactor': ['src/utils.ts', 'src/index.ts', 'src/index.test.ts', 'review'],
    'file-improve': ['src/index.ts', 'src/utils.ts'],
    'iterative-refine': ['README.md', 'document.md', 'summary.md'],
    'document-review': ['review_result.md'],
    'research-enhance': ['document.md', 'README.md', 'NOTES.md', 'chapter1.md', 'chapter2.md', 'cv.md'],
    'translation-pipeline': ['translations'],
    'project-overview': ['analysis'],
    'topic-deep-dive': ['reports'],
    'log-triage': ['output'],
    'data-analysis': ['output'],
    'fact-check-pipeline': ['document.md'],
  };

  const paths = outputPaths[templateId] ?? [];
  for (const p of paths) {
    const src = resolve(workspacePath, p);
    if (existsSync(src)) {
      const dest = resolve(artifactsDir, p);
      try {
        mkdirSync(resolve(artifactsDir, dirname(p)), { recursive: true });
        cpSync(src, dest, { recursive: true });
      } catch {
        // ignore copy errors
      }
    }
  }

  // Also copy any untracked files/dirs listed in git status
  try {
    const status = execSync('git status --porcelain', {
      cwd: workspacePath,
      encoding: 'utf-8',
      timeout: 10000,
    });
    for (const line of status.split('\n')) {
      if (line.startsWith('?? ')) {
        const untrackedPath = line.slice(3).replace(/\/$/, '');
        // Skip node_modules etc.
        if (untrackedPath.startsWith('node_modules')) continue;
        const src = resolve(workspacePath, untrackedPath);
        const dest = resolve(artifactsDir, untrackedPath);
        if (existsSync(src)) {
          try {
            mkdirSync(resolve(artifactsDir, dirname(untrackedPath)), { recursive: true });
            cpSync(src, dest, { recursive: true });
          } catch {
            // ignore
          }
        }
      }
    }
  } catch {
    // ignore
  }
}

/**
 * Reset the test workspace to its initial git state.
 */
export function resetWorkspace(workspacePath: string): void {
  try {
    execSync('git checkout -- .', { cwd: workspacePath, timeout: 10000 });
    // Clean everything including output dirs — no excludes so each template starts fresh
    execSync('git clean -fd', {
      cwd: workspacePath,
      timeout: 10000,
    });
  } catch {
    // If git reset fails, re-run workspace script
    execSync('bash /home/flo/Entwicklung/tools/autotrigger/scripts/create-test-workspace.sh', {
      timeout: 15000,
    });
  }
}

/**
 * Save run result JSON to the results directory.
 */
export function saveRunResult(
  tier: 'shell' | 'simple-ai',
  templateId: string,
  run: RunResponse,
  resultsBaseDir: string,
): void {
  const dir = resolve(resultsBaseDir, tier);
  mkdirSync(dir, { recursive: true });
  writeFileSync(
    resolve(dir, `${templateId}.json`),
    JSON.stringify(run, null, 2),
  );
}
