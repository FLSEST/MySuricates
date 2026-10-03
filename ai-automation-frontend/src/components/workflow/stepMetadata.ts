import type { StepResult } from '../../types';
import type { ForEachItemState } from '../../types/websocket';

/** Block type of a step: top-level field, else `metadata.blockType` (older backends only persist it there). */
export function stepBlockType(step: StepResult): string {
  if (step.blockType) return step.blockType;
  const fromMetadata = step.metadata?.blockType;
  return typeof fromMetadata === 'string' ? fromMetadata : '';
}

/**
 * The block's step note (`config.description`), written by the backend as `metadata.blockDescription`
 * (TODO 31b7aca8); null when the block has none. todo-create/-update never carry it — there the
 * description is the TODO text.
 */
export function stepBlockNote(step: StepResult): string | null {
  const note = step.metadata?.blockDescription;
  return typeof note === 'string' && note.trim() ? note : null;
}

/** Block name of a step: top-level field, else `metadata.blockName`; null for unnamed blocks. */
export function stepBlockName(step: StepResult): string | null {
  if (step.blockName) return step.blockName;
  const fromMetadata = step.metadata?.blockName;
  return typeof fromMetadata === 'string' && fromMetadata ? fromMetadata : null;
}

/**
 * Top-level block position of a step (`metadata.blockIndex`), null for runs from before the backend wrote it.
 * Not `stepIndex`: wrapper blocks draw one index per item or iteration, so the index runs ahead of the position.
 */
export function stepBlockIndex(step: StepResult): number | null {
  const fromMetadata = step.metadata?.blockIndex;
  return typeof fromMetadata === 'number' ? fromMetadata : null;
}

/**
 * The newest step of each top-level block, keyed by block position — what the flow diagram colours and
 * jumps to. Steps without `blockIndex` fall back to their place in the index-ordered list: the backend
 * persists exactly one step per top-level block, in block order (see `ResumePlan`). A resumed block has an
 * older attempt next to its new one; the higher step index wins.
 */
export function latestStepByBlock(steps: readonly StepResult[]): Map<number, StepResult> {
  const byBlock = new Map<number, StepResult>();
  [...steps]
    .sort((a, b) => a.stepIndex - b.stepIndex)
    .forEach((step, position) => byBlock.set(stepBlockIndex(step) ?? position, step));
  return byBlock;
}

/**
 * What decided the step's outcome: an AI call, fixed logic, or a person. `unknown` is a wrapper whose result
 * metadata is not there (still running, or a run from before wrappers recorded it) — no guessing.
 */
export type StepKind = 'ai' | 'deterministic' | 'human' | 'unknown';

const HUMAN_BLOCK_TYPES = new Set(['approval', 'human-input']);
/** Blocks that always call the AI — used while their AI metadata is not written yet (running, failed early). */
const ALWAYS_AI_BLOCK_TYPES = new Set(['simple', 'agentic', 'skill-capability', 'compress-ai']);
const WRAPPER_BLOCK_TYPES = new Set(['foreach', 'while', 'retry', 'conditional', 'switch']);
/** Keys every finished wrapper writes; together with a missing `aiRequest` they prove no inner AI call ran. */
const WRAPPER_RESULT_KEYS = ['totalItems', 'iterationCount', 'attempts', 'conditionMet', 'matchedCase'];

/**
 * Integration steps write their integration provider (`feed`, `open-meteo` …) into `metadata.provider`, so the
 * key is no AI evidence there. `executionMode` tells them apart when the step carries no block type.
 */
function isIntegrationStep(step: StepResult, blockType: string): boolean {
  return blockType === 'integration' || step.metadata?.executionMode === 'integration';
}

/**
 * Classifies a step by what actually ran: wrappers (foreach, while, retry, conditional, switch) count as AI only
 * when an inner AI call happened — the backend then carries `aiRequest` (older runs: `provider`) onto the step.
 */
export function stepKind(step: StepResult): StepKind {
  const blockType = stepBlockType(step);
  if (HUMAN_BLOCK_TYPES.has(blockType)) return 'human';
  const { provider, aiRequest } = stepMetadataSummary(step);
  const legacyAiProvider = provider !== null && !isIntegrationStep(step, blockType);
  if (aiRequest || legacyAiProvider || ALWAYS_AI_BLOCK_TYPES.has(blockType)) return 'ai';
  if (WRAPPER_BLOCK_TYPES.has(blockType)) {
    const metadata = step.metadata ?? {};
    return WRAPPER_RESULT_KEYS.some((key) => metadata[key] !== undefined) ? 'deterministic' : 'unknown';
  }
  return 'deterministic';
}

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

export interface CompressionFacts {
  source: { block: string | null; stepIndex: number | null; status: string | null; exitCode: number | null } | null;
  inChars: number;
  outChars: number;
  maxChars: number | null;
  capped: boolean;
  stages: Array<{ op: string; inChars: number; outChars: number; note: string | null }>;
}

/** The `metadata.compression` contract of compress / compress-ai steps; null for every other step. */
export function compressionFacts(step: StepResult): CompressionFacts | null {
  const raw = step.metadata?.compression;
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const c = raw as Record<string, unknown>;
  const num = (v: unknown) => (typeof v === 'number' ? v : null);
  const str = (v: unknown) => (typeof v === 'string' ? v : null);
  const src = c.source && typeof c.source === 'object' ? (c.source as Record<string, unknown>) : null;
  return {
    source: src ? { block: str(src.block), stepIndex: num(src.stepIndex), status: str(src.status), exitCode: num(src.exitCode) } : null,
    inChars: num(c.inChars) ?? 0,
    outChars: num(c.outChars) ?? 0,
    maxChars: num(c.maxChars),
    capped: c.capped === true,
    stages: Array.isArray(c.stages)
      ? c.stages
          .filter((s): s is Record<string, unknown> => !!s && typeof s === 'object')
          .map((s) => ({ op: str(s.op) ?? '?', inChars: num(s.inChars) ?? 0, outChars: num(s.outChars) ?? 0, note: str(s.note) }))
      : [],
  };
}

/** WAITING_APPROVAL counts as running only while its run still runs (TODO 4eea7a87). */
export function stepWaitsForPerson(step: StepResult, runActive: boolean): boolean {
  return step.status === 'WAITING_APPROVAL' && runActive;
}
