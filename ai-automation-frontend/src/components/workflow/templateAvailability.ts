import type { ModuleInfo, ModuleName, WorkflowTemplate } from '../../types';
import { getExecutionModes } from '../shared/executionModes';

/**
 * Modules a template needs that this instance has switched off (TODO 560d18dd). A module whose
 * state is unknown counts as on, like `useModuleEnabled`: an older backend without module info, or
 * one without `requiredModules`, hides nothing.
 */
export function missingModules(template: Pick<WorkflowTemplate, 'requiredModules'>, modules?: ModuleInfo[]): ModuleName[] {
  const required = template.requiredModules ?? [];
  if (!modules || required.length === 0) return [];
  return required.filter((name) => modules.find((m) => m.name === name)?.enabled === false);
}

/** What runs on this instance, and what needs a module switched on first — order kept. */
export function splitByAvailability<T extends Pick<WorkflowTemplate, 'requiredModules'>>(
  templates: T[], modules?: ModuleInfo[],
): { runnable: T[]; needsModule: T[] } {
  const runnable: T[] = [];
  const needsModule: T[] = [];
  for (const template of templates) {
    (missingModules(template, modules).length > 0 ? needsModule : runnable).push(template);
  }
  return { runnable, needsModule };
}

/** The server's `needsAi`; for an older backend without it, the same rule on the blocks. */
export function templateNeedsAi(template: Pick<WorkflowTemplate, 'needsAi' | 'blocks'>): boolean {
  return template.needsAi ?? getExecutionModes(template.blocks ?? []).includes('ai');
}
