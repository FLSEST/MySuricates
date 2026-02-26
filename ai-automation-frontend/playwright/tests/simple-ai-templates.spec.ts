import { test, expect } from '@playwright/test';
import { execSync } from 'child_process';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';
import {
  createProject,
  getTemplates,
  createWorkflow,
  executeWorkflow,
  pollRunCompletion,
  saveRunResult,
  captureArtifacts,
  resetWorkspace,
  TEST_PREFIX,
  type TemplateResponse,
} from '../fixtures/api-helpers';
import { simpleAiTemplates } from '../fixtures/template-variables';

const __dir = dirname(fileURLToPath(import.meta.url));
const RESULTS_DIR = resolve(__dir, '../results');
const WORKSPACE = '/tmp/autopilot-testbed';

let projectId: string;
let templates: TemplateResponse[];

// No serial mode — tests are independent and should run even if one fails

test.beforeAll(async () => {
  // 1. Create test workspace
  execSync('bash /home/flo/Entwicklung/tools/autotrigger/scripts/create-test-workspace.sh', {
    timeout: 15000,
  });

  // 2. Create project via API
  const project = await createProject(`${TEST_PREFIX} AI Tests ${Date.now()}`);
  projectId = project.id;

  // 3. Fetch all templates
  templates = await getTemplates();
});

for (const templateConfig of simpleAiTemplates) {
  test(`template: ${templateConfig.templateId}`, async ({ page }) => {
    // Find template by English name (DB IDs may be UUIDs from legacy seeding)
    const template = templates.find((t) => t.name['en'] === templateConfig.nameEn);
    expect(template, `Template '${templateConfig.nameEn}' not found in API response`).toBeTruthy();

    // Reset workspace between templates
    resetWorkspace(WORKSPACE);

    // Create workflow from template (use DB id for templateId reference)
    const workflow = await createWorkflow({
      projectId,
      name: `${TEST_PREFIX} ${templateConfig.nameEn}`,
      description: `Playwright test of template: ${templateConfig.templateId}`,
      blocks: template!.blocks,
      instructions: template!.instructions ?? undefined,
      model: template!.model ?? undefined,
      templateId: template!.id,
    });

    // Execute via API
    const run = await executeWorkflow(workflow.id, templateConfig.variables);

    // Navigate to run page
    await page.goto(`/workflows/${workflow.id}/run/${run.id}`);
    await page.waitForLoadState('networkidle');

    // Poll until done
    const timeoutMs = templateConfig.timeoutMs ?? 10 * 60 * 1000;
    const completedRun = await pollRunCompletion(workflow.id, run.id, { timeoutMs });

    // Reload to show final state
    await page.reload();
    await page.waitForLoadState('networkidle');

    // Take screenshot
    await page.screenshot({
      path: resolve(RESULTS_DIR, 'simple-ai', `${templateConfig.templateId}.png`),
      fullPage: true,
    });

    // Save run result JSON
    saveRunResult('simple-ai', templateConfig.templateId, completedRun, RESULTS_DIR);

    // Capture artifacts
    captureArtifacts(templateConfig.templateId, WORKSPACE, resolve(RESULTS_DIR, 'simple-ai'));

    // Assert completion
    expect(
      completedRun.status,
      `Template '${templateConfig.templateId}' ended with status ${completedRun.status}`,
    ).toBe('COMPLETED');
  });
}
