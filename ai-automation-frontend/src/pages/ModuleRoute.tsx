import { type ReactNode } from 'react';
import { useModuleEnabled } from '../hooks/useModules';
import { ModuleDisabled } from './ModuleDisabled';
import type { FeatureModuleName } from '../types';

/** Renders the page only when its module is enabled on this backend; otherwise explains how to enable it. */
export function ModuleRoute({ module, children }: { module: FeatureModuleName; children: ReactNode }) {
  const enabled = useModuleEnabled(module);
  return enabled ? <>{children}</> : <ModuleDisabled module={module} />;
}
