/**
 * Copyable configuration snippets for switching a module at startup.
 * `automation.modules.webhooks` + true →
 *   automation:
 *     modules:
 *       webhooks: true
 */
export function yamlSnippet(property: string, value: boolean): string {
  const parts = property.split('.');
  return parts
    .map((part, depth) => `${'  '.repeat(depth)}${part}:${depth === parts.length - 1 ? ` ${value}` : ''}`)
    .join('\n');
}

export function envSnippet(envVar: string, value: boolean): string {
  return `${envVar}=${value}`;
}
