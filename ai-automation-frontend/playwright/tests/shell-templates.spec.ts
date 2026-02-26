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
import { shellTemplates } from '../fixtures/template-variables';

const __dir = dirname(fileURLToPath(import.meta.url));
const RESULTS_DIR = resolve(__dir, '../results');
const WORKSPACE = '/tmp/autopilot-testbed';

let projectId: string;
let templates: TemplateResponse[];

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  // 1. Create test workspace
  execSync('bash /home/flo/Entwicklung/tools/autotrigger/scripts/create-test-workspace.sh', {
    timeout: 15000,
  });

  // 2. Create project via API
  const project = await createProject(`${TEST_PREFIX} Shell Tests ${Date.now()}`);
  projectId = project.id;

  // 3. Fetch all templates
  templates = await getTemplates();
});

for (const templateConfig of shellTemplates) {
  test(`template: ${templateConfig.templateId}`, async ({ page }) => {
    // Find template definition
    const template = templates.find((t) => t.id === templateConfig.templateId);
    expect(template, `Template '${templateConfig.templateId}' not found`).toBeTruthy();

    // Reset workspace between templates
    resetWorkspace(WORKSPACE);

    // Create workflow from template
    const templateName = template!.name['en'] ?? template!.name['de'] ?? templateConfig.templateId;
    const workflow = await createWorkflow({
      projectId,
      name: `${TEST_PREFIX} ${templateName}`,
      description: `Playwright test of template: ${templateConfig.templateId}`,
      blocks: template!.blocks,
      instructions: template!.instructions ?? undefined,
      model: template!.model ?? undefined,
      templateId: templateConfig.templateId,
    });

    // Navigate to workflow page
    await page.goto(`/workflows/${workflow.id}`);
    await page.waitForLoadState('networkidle');

    // Click Execute button
    const executeButton = page.getByRole('button', { name: /execute/i });
    await expect(executeButton).toBeVisible({ timeout: 10000 });

    const hasVariables = Object.keys(templateConfig.variables).length > 0;

    if (hasVariables) {
      // Click execute to open dialog
      await executeButton.click();

      // Fill in variables in the dialog
      for (const [varName, varValue] of Object.entries(templateConfig.variables)) {
        const input = page.locator(`input`).filter({ has: page.locator(`..`).filter({ hasText: varName }) });
        // Try by label first, then by placeholder
        const labelInput = page.getByLabel(varName);
        if (await labelInput.isVisible({ timeout: 2000 }).catch(() => false)) {
          await labelInput.fill(varValue);
        } else {
          // Fallback: find input by its associated label text
          const inputField = page.locator(`label:has-text("${varName}") + input, label:has-text("${varName}") input`).first();
          await inputField.fill(varValue);
        }
      }

      // Click the execute button inside the dialog
      const dialogExecuteBtn = page.locator('[role="dialog"] button').filter({ hasText: /execute/i });
      await dialogExecuteBtn.click();
    } else {
      // No variables — direct execution via API (faster and more reliable)
      const run = await executeWorkflow(workflow.id, {});

      // Navigate to the run page
      await page.goto(`/workflows/${workflow.id}/run/${run.id}`);
    }

    // Wait for navigation to run page (URL should contain /run/)
    await page.waitForURL(/\/run\//, { timeout: 15000 }).catch(() => {
      // If we already navigated via API, we're already on the run page
    });

    // Poll for completion via API
    const currentUrl = page.url();
    const runIdMatch = currentUrl.match(/\/run\/([^/]+)/);

    let runId: string;
    if (runIdMatch) {
      runId = runIdMatch[1];
    } else {
      // Execute via API if UI execution didn't produce a run URL
      const run = await executeWorkflow(workflow.id, templateConfig.variables);
      runId = run.id;
      await page.goto(`/workflows/${workflow.id}/run/${runId}`);
    }

    // Poll until done (5 min timeout for shell templates)
    const completedRun = await pollRunCompletion(workflow.id, runId, {
      timeoutMs: 5 * 60 * 1000,
    });

    // Reload to show final state
    await page.reload();
    await page.waitForLoadState('networkidle');

    // Take screenshot
    await page.screenshot({
      path: resolve(RESULTS_DIR, 'shell', `${templateConfig.templateId}.png`),
      fullPage: true,
    });

    // Save run result JSON
    saveRunResult('shell', templateConfig.templateId, completedRun, RESULTS_DIR);

    // Capture artifacts
    captureArtifacts(templateConfig.templateId, WORKSPACE, resolve(RESULTS_DIR, 'shell'));

    // Assert completion
    expect(
      completedRun.status,
      `Template '${templateConfig.templateId}' ended with status ${completedRun.status}`,
    ).toBe('COMPLETED');
  });
}
