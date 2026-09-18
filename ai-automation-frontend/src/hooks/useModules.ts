import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, apiErrorMessage } from '../api/client';
import {
  capabilityModules, useSystemCapabilities, writeCachedModules, SYSTEM_CAPABILITIES_KEY,
  type SystemCapabilities,
} from './useSystemCapabilities';
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

/** Module state, from the shared capabilities query (see {@link useSystemCapabilities}). */
export function useSystemModules() {
  return useSystemCapabilities(capabilityModules);
}

function useModuleSwitch<TArg>(request: (arg: TArg) => Promise<SystemModules>, successMessage: (t: ReturnType<typeof useT>) => string) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  return useMutation({
    mutationFn: request,
    onSuccess: (value) => {
      // Takes effect immediately: navigation, routes and gated queries follow this cache
      qc.setQueryData<SystemCapabilities>(SYSTEM_CAPABILITIES_KEY, (old) => ({
        ...old, preset: value.preset, modulesUiEditable: value.uiEditable, modules: value.modules,
      }));
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
