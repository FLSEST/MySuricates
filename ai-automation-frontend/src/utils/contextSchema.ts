import type { Workflow, ContextVariableSpec } from '../types';

export interface ContextVariableInfo {
  optional: string[];
  descriptions: Record<string, string>;
  placeholders: Record<string, string>;
}

export function readContextSchema(workflow: Pick<Workflow, 'metadata'> | undefined | null): ContextVariableInfo {
  const schema = workflow?.metadata?.contextSchema as Record<string, ContextVariableSpec> | undefined;
  const optional: string[] = [];
  const descriptions: Record<string, string> = {};
  const placeholders: Record<string, string> = {};

  if (schema) {
    for (const [name, spec] of Object.entries(schema)) {
      if (spec?.required === false) optional.push(name);
      if (spec?.description) descriptions[name] = spec.description;
      if (spec?.placeholder) placeholders[name] = spec.placeholder;
    }
  }

  return { optional, descriptions, placeholders };
}
