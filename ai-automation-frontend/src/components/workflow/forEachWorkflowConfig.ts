/** The `workflow_id` of a foreach config, or '' (also for config text that is no JSON object yet). */
export function forEachWorkflowId(configJson: string): string {
  try {
    const config = JSON.parse(configJson) as unknown;
    return config && typeof config === 'object' && typeof (config as { workflow_id?: unknown }).workflow_id === 'string'
      ? (config as { workflow_id: string }).workflow_id
      : '';
  } catch {
    return '';
  }
}

/**
 * Sets or clears `workflow_id` and keeps every other key (TODO a5c9fbbf). `command` and `workflow_id` exclude each
 * other: choosing a workflow drops the command, clearing it brings back an empty command to fill in. Config text that
 * is no JSON object stays as it is — the editor already marks it.
 */
export function withForEachWorkflow(configJson: string, workflowId: string): string {
  let config: Record<string, unknown>;
  try {
    const parsed = JSON.parse(configJson) as unknown;
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return configJson;
    config = { ...(parsed as Record<string, unknown>) };
  } catch {
    return configJson;
  }
  if (workflowId) {
    config.workflow_id = workflowId;
    delete config.command;
  } else {
    delete config.workflow_id;
    if (config.command === undefined) config.command = '';
  }
  return JSON.stringify(config, null, 2);
}
