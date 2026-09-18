import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { api } from '../api/client';
import type { ModuleInfo, ModulePreset, SystemModules } from '../types';

/**
 * The one query over `GET /api/v1/system/capabilities`. The response is large (it carries the
 * complete route list), and both {@link useSystemModules} and {@link useBlockTypes} need a slice
 * of it — before TODO a42969a6 they asked twice per workflow detail page, which counts twice
 * against the rate limit of a publicly reachable instance. Callers pass a selector; the request
 * itself happens once.
 *
 * Both browser caches stay separate on purpose (`mysuricates-modules`, `mysuricates-block-types`):
 * they seed the query so navigation and the block-type select do not flicker after a reload or a
 * 429. `initialData` (not `placeholderData`, which v5 only applies while pending) keeps the last
 * known state visible in the error case too; `initialDataUpdatedAt: 0` marks it stale at once, so
 * the server is still asked exactly once per page view.
 */

/** The fields of the capabilities response the frontend reads. */
export interface SystemCapabilities {
  preset?: ModulePreset;
  modulesUiEditable?: boolean;
  modules?: ModuleInfo[];
  blockTypes?: Array<{ type?: string }>;
}

export const SYSTEM_CAPABILITIES_KEY = ['system', 'capabilities'] as const;

const MODULES_CACHE_KEY = 'mysuricates-modules';
const BLOCK_TYPES_CACHE_KEY = 'mysuricates-block-types';

/** Module state of a capabilities response; an older backend without module info means "all on". */
export function capabilityModules(caps: SystemCapabilities): SystemModules {
  return {
    preset: caps.preset ?? 'full',
    uiEditable: caps.modulesUiEditable ?? false,
    modules: caps.modules ?? [],
  };
}

/** Block types a capabilities response names; empty for an older backend that reports none. */
export function capabilityBlockTypes(caps: SystemCapabilities): string[] {
  return (caps.blockTypes ?? [])
    .map((b) => b.type)
    .filter((t): t is string => typeof t === 'string' && t.length > 0);
}

function readCachedModules(): SystemModules | undefined {
  try {
    const raw = localStorage.getItem(MODULES_CACHE_KEY);
    return raw ? (JSON.parse(raw) as SystemModules) : undefined;
  } catch {
    return undefined;
  }
}

export function writeCachedModules(value: SystemModules) {
  try {
    localStorage.setItem(MODULES_CACHE_KEY, JSON.stringify(value));
  } catch {
    // per-browser convenience only (avoids nav flicker on reload)
  }
}

function readCachedBlockTypes(): string[] | undefined {
  try {
    const raw = localStorage.getItem(BLOCK_TYPES_CACHE_KEY);
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
    localStorage.setItem(BLOCK_TYPES_CACHE_KEY, JSON.stringify(value));
  } catch {
    // per-browser convenience only (avoids a flicker in the block type select on reload)
  }
}

/** Seeds the query from both browser caches; undefined while neither has been written yet. */
function readCachedCapabilities(): SystemCapabilities | undefined {
  const modules = readCachedModules();
  const blockTypes = readCachedBlockTypes();
  if (!modules && !blockTypes) return undefined;
  return {
    ...(modules ? { preset: modules.preset, modulesUiEditable: modules.uiEditable, modules: modules.modules } : {}),
    ...(blockTypes ? { blockTypes: blockTypes.map((type) => ({ type })) } : {}),
  };
}

/**
 * Reads one slice of the shared capabilities query. Pass a selector defined outside the component
 * (module level) so its identity stays stable across renders.
 */
export function useSystemCapabilities<T>(
  select: (caps: SystemCapabilities) => T,
): UseQueryResult<T, Error> {
  return useQuery({
    queryKey: SYSTEM_CAPABILITIES_KEY,
    queryFn: async (): Promise<SystemCapabilities> => {
      const caps = await api.get<SystemCapabilities>('/system/capabilities');
      writeCachedModules(capabilityModules(caps));
      const types = capabilityBlockTypes(caps);
      if (types.length > 0) writeCachedBlockTypes(types);
      return caps;
    },
    select,
    initialData: readCachedCapabilities,
    initialDataUpdatedAt: 0,
    // An hour is practically "once per page view"; module state changes with a restart or the
    // settings switches below, block types with a backend deploy.
    staleTime: 60 * 60 * 1000,
  });
}
