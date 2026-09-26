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
  /** `automation.executions.allow-delete` (startup-only); absent on older backends = allowed. */
  runDeletionAllowed?: boolean;
  /** What this instance got from the host for the AI CLIs; absent on older backends. */
  containerAccess?: ContainerAccess | null;
}

export type AiFromHost = 'programs' | 'login' | 'all';

/**
 * Decided before the start by scripts/lib/host-mounts.sh (262948a4) — the app can show it, never
 * change it. Only names and yes/no values: the backend never sends a token or file content.
 */
export interface ContainerAccess {
  runtime: 'container' | 'host';
  /** null on the host */
  aiFromHost: AiFromHost | null;
  dockerSocket: boolean;
  /** Where the switches are changed (a file, or how the starter is called); null on the host. */
  switchSource: string | null;
  /** Prefix of the switch names there, e.g. `STABLE_`; null on the host. */
  switchPrefix: string | null;
  providers: Array<{
    id: 'claude' | 'codex' | 'pi';
    selected: boolean;
    program: boolean;
    /** null only when no provider of this id is registered */
    login: boolean | null;
  }>;
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

const selectRunDeletionAllowed = (caps: SystemCapabilities) => caps.runDeletionAllowed !== false;
const selectContainerAccess = (caps: SystemCapabilities) => caps.containerAccess ?? null;

/** What this instance got from the host for the AI CLIs (read-only); null on older backends. */
export function useContainerAccess(): UseQueryResult<ContainerAccess | null, Error> {
  return useSystemCapabilities(selectContainerAccess);
}

/**
 * Whether this instance lets runs be deleted (`automation.executions.allow-delete`). Only hides the
 * delete buttons — the backend refuses with 403 RUN_DELETION_DISABLED regardless.
 */
export function useRunDeletionAllowed(): boolean {
  const { data } = useSystemCapabilities(selectRunDeletionAllowed);
  return data ?? true;
}
