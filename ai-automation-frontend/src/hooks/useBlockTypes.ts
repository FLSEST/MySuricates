import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { BLOCK_TYPES } from '../types';

/**
 * The block types this backend can actually execute, from GET /api/v1/system/capabilities
 * (fed there by the `BlockTypeRegistry`, i.e. the registered BlockExecutors — TODO 64966be0).
 *
 * The static `BLOCK_TYPES` in `src/types/index.ts` stays as the fallback for the first paint, a
 * failed request and an older backend that reports none; `types/__tests__/blockTypes.test.ts`
 * keeps it in step with the backend list. A type only the server knows is offered with its raw
 * name — the editor has no icon or label for it, but it can be selected and saved.
 *
 * Shares the endpoint with {@link useSystemModules} but not its query: this one is mounted only
 * by the workflow editor, and block types change with a backend deploy, not at runtime.
 */

const CACHE_KEY = 'mysuricates-block-types';

const FALLBACK: readonly string[] = BLOCK_TYPES;

function readCachedBlockTypes(): string[] | undefined {
  try {
    const raw = localStorage.getItem(CACHE_KEY);
    if (!raw) return undefined;
    const parsed = JSON.parse(raw) as unknown;
    return Array.isArray(parsed) && parsed.every((t) => typeof t === 'string' && t) && parsed.length > 0
      ? (parsed as string[])
      : undefined;
  } catch {
    return undefined;
  }
}

function writeCachedBlockTypes(value: string[]) {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify(value));
  } catch {
    // per-browser convenience only (avoids a flicker in the block type select on reload)
  }
}

export function useBlockTypes(): readonly string[] {
  const { data } = useQuery({
    queryKey: ['system', 'blockTypes'],
    queryFn: async (): Promise<string[]> => {
      const caps = await api.get<{ blockTypes?: Array<{ type?: string }> }>('/system/capabilities');
      const types = (caps.blockTypes ?? [])
        .map((b) => b.type)
        .filter((t): t is string => typeof t === 'string' && t.length > 0);
      // An older backend answers without block types — keep the built-in list rather than none
      if (types.length === 0) return [...FALLBACK];
      writeCachedBlockTypes(types);
      return types;
    },
    // `initialData` rather than `placeholderData`: the latter only applies while pending, so a
    // failed request would leave the select empty instead of on the last known list (see useModules).
    initialData: () => readCachedBlockTypes() ?? [...FALLBACK],
    // Epoch 0 ⇒ the seeded list counts as stale at once, so the query still asks the server once.
    initialDataUpdatedAt: 0,
    staleTime: 24 * 60 * 60 * 1000,
  });
  return data;
}
