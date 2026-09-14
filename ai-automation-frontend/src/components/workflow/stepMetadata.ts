import type { StepResult } from '../../types';
import type { ForEachItemState } from '../../types/websocket';

/**
 * Provider/model facts of a step, read from the persisted step metadata.
 * Host steps write them directly; wrapper blocks (foreach, while, conditional, switch, retry,
 * skill-capability) carry the keys of their first inner AI call.
 */
export function stepMetadataSummary(step: StepResult) {
  const metadata = step.metadata ?? {};
  const provider = typeof metadata.provider === 'string' ? metadata.provider : null;
  const model = typeof metadata.model === 'string' ? metadata.model : null;
  const requestedModel = typeof metadata.requestedModel === 'string' ? metadata.requestedModel : null;
  const aiSystemPrompt = typeof metadata.aiSystemPrompt === 'string' ? metadata.aiSystemPrompt : null;
  const aiRequest = metadata.aiRequest && typeof metadata.aiRequest === 'object' && !Array.isArray(metadata.aiRequest)
    ? (metadata.aiRequest as Record<string, unknown>)
    : null;

  return {
    provider,
    model,
    requestedModel,
    showRequestedModel: requestedModel != null && requestedModel !== model,
    aiSystemPrompt,
    aiRequest,
  };
}

/** Metadata keys of wrapper blocks worth a `key=value` pill next to provider/model. */
const WRAPPER_FACT_KEYS = ['attempts', 'maxAttempts', 'iterationCount', 'conditionMet', 'matchedCase'] as const;

export function wrapperFacts(step: StepResult): Array<[string, string]> {
  const metadata = step.metadata ?? {};
  return WRAPPER_FACT_KEYS
    .filter((key) => metadata[key] !== undefined && metadata[key] !== null)
    .map((key) => [key, formatMetadataValue(metadata[key])]);
}

/** Renders nested objects/arrays as JSON instead of `[object Object]`. */
export function formatMetadataValue(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  return String(value);
}

/**
 * Items of a finished foreach step as persisted by the backend (`metadata.items`), in the shape of
 * the live WebSocket state so the same list component can render both.
 */
export function persistedForEachItems(step: StepResult): ForEachItemState[] {
  const raw = step.metadata?.items;
  if (!Array.isArray(raw)) return [];
  const items: ForEachItemState[] = [];
  raw.forEach((entry, position) => {
    if (!entry || typeof entry !== 'object') return;
    const record = entry as Record<string, unknown>;
    const itemIndex = typeof record.index === 'number' ? record.index : position;
    const item = typeof record.item === 'string' ? record.item : String(record.item ?? '');
    const status = record.status === 'COMPLETED' ? 'completed' : 'failed';
    items.push({ itemIndex, item, status });
  });
  return items;
}

export function persistedTotalItems(step: StepResult): number | null {
  const total = step.metadata?.totalItems;
  return typeof total === 'number' ? total : null;
}
