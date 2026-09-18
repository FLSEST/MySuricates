import { BLOCK_TYPES } from '../types';
import { capabilityBlockTypes, useSystemCapabilities, type SystemCapabilities } from './useSystemCapabilities';

/**
 * The block types this backend can actually execute, from GET /api/v1/system/capabilities
 * (fed there by the `BlockTypeRegistry`, i.e. the registered BlockExecutors — TODO 64966be0).
 *
 * The static `BLOCK_TYPES` in `src/types/index.ts` stays as the fallback for the first paint, a
 * failed request and an older backend that reports none; `types/__tests__/blockTypes.test.ts`
 * keeps it in step with the backend list. A type only the server knows is offered with its raw
 * name — the editor has no icon or label for it, but it can be selected and saved.
 *
 * Shares the endpoint <em>and</em> the query with {@link useSystemModules} since TODO a42969a6:
 * both derive their slice from one request instead of asking the large response twice.
 */

const FALLBACK: readonly string[] = BLOCK_TYPES;

/** Module level so the selector identity stays stable across renders. */
function selectBlockTypes(caps: SystemCapabilities): readonly string[] {
  const types = capabilityBlockTypes(caps);
  return types.length > 0 ? types : FALLBACK;
}

export function useBlockTypes(): readonly string[] {
  const { data } = useSystemCapabilities(selectBlockTypes);
  // undefined only until the query is seeded or answered — the built-in list carries that moment
  return data ?? FALLBACK;
}
