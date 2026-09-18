import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import { useToast } from './useToast';
import { useT } from '../i18n';
import type { ModuleInfo, ModuleName, SystemModules } from '../types';

/**
 * Optional modules are decided by the backend at startup (automation.modules.*,
 * automation.hardening.*). Only if the startup configuration sets
 * automation.modules.ui-editable can feature modules also be switched in the settings.
 * The frontend mirrors the state to hide navigation, routes and widgets — the backend
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
      const caps = await api.get<Partial<SystemModules> & { modulesUiEditable?: boolean }>('/system/capabilities');
      const value: SystemModules = {
        preset: caps.preset ?? 'full',
        uiEditable: caps.modulesUiEditable ?? false,
        modules: caps.modules ?? [],
      };
      writeCachedModules(value);
      return value;
    },
    // `initialData` statt `placeholderData`: letzteres greift in v5 nur bei
    // status === 'pending'. Nach einem 429 galt damit wieder jedes Modul als „an" und
    // Navigation und Widgets flackerten (b13509f3). Mit initialData bleibt der zuletzt
    // bekannte Stand auch im Fehlerfall stehen.
    initialData: readCachedModules,
    // Epoch 0 ⇒ der geseedete Stand ist sofort stale, die Query fragt den Server also
    // trotzdem einmal. Das braucht eine endliche staleTime (mit Infinity wäre nichts je
    // stale); eine Stunde entspricht praktisch dem bisherigen „einmal pro Seitenaufruf",
    // Änderungen kommen ohnehin aus einem Neustart oder aus den Schaltern unten.
    initialDataUpdatedAt: 0,
    staleTime: 60 * 60 * 1000,
  });
}

function useModuleSwitch<TArg>(request: (arg: TArg) => Promise<SystemModules>, successMessage: (t: ReturnType<typeof useT>) => string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: request,
    onSuccess: (value) => {
      // Takes effect immediately: navigation, routes and gated queries follow this cache
      qc.setQueryData(['system', 'modules'], value);
      writeCachedModules(value);
      toast.success(successMessage(t));
    },
    onError: (err) => toast.error(apiErrorMessage(err, t.modules.switchFailed)),
  });
}

/** Switch a feature module (requires automation.modules.ui-editable on the backend). */
export function useSetModuleEnabled() {
  return useModuleSwitch(
    ({ name, enabled }: { name: ModuleName; enabled: boolean }) =>
      api.put<SystemModules>(`/system/modules/${name}`, { enabled }),
    (t) => t.modules.switched,
  );
}

/** Drop the settings switch: the module follows the startup configuration again. */
export function useResetModule() {
  return useModuleSwitch(
    (name: ModuleName) => api.delete<SystemModules>(`/system/modules/${name}/override`),
    (t) => t.modules.resetDone,
  );
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
