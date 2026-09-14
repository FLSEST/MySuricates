import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { ModuleInfo, ModuleName, SystemModules } from '../types';

/**
 * Optional modules are decided by the backend at startup (automation.modules.*,
 * automation.hardening.*) and cannot be switched from the UI. The frontend only
 * mirrors that decision to hide navigation, routes and widgets — the backend
 * enforces it regardless (404 MODULE_DISABLED).
 */

const CACHE_KEY = 'mysuricates-modules';

function readCachedModules(): SystemModules | undefined {
  try {
    const raw = localStorage.getItem(CACHE_KEY);
    return raw ? (JSON.parse(raw) as SystemModules) : undefined;
  } catch {
    return undefined;
  }
}

function writeCachedModules(value: SystemModules) {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify(value));
  } catch {
    // per-browser convenience only (avoids nav flicker on reload)
  }
}

export function useSystemModules() {
  return useQuery({
    queryKey: ['system', 'modules'],
    queryFn: async (): Promise<SystemModules> => {
      const caps = await api.get<Partial<SystemModules>>('/system/capabilities');
      const value: SystemModules = { preset: caps.preset ?? 'full', modules: caps.modules ?? [] };
      writeCachedModules(value);
      return value;
    },
    // Modules only change with a backend restart; a reload picks that up.
    staleTime: Infinity,
    placeholderData: readCachedModules,
  });
}

export function useModuleInfo(name: ModuleName): ModuleInfo | undefined {
  const { data } = useSystemModules();
  return data?.modules.find((m) => m.name === name);
}

/**
 * True unless the backend has reported the module as disabled. While the status is
 * unknown (first load, older backend without module info) this is optimistic: at worst
 * a request answers 404 MODULE_DISABLED once, which is harmless.
 */
export function useModuleEnabled(name: ModuleName): boolean {
  const info = useModuleInfo(name);
  return info ? info.enabled : true;
}

export function isModuleDisabledError(error: unknown): boolean {
  return !!error && typeof error === 'object' && 'code' in error
    && (error as { code?: string }).code === 'MODULE_DISABLED';
}
