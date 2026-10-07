import { Link } from 'react-router-dom';
import { PackageX } from 'lucide-react';
import { EmptyState } from '../components/shared/EmptyState';
import { ConfigSnippet } from '../components/settings/ConfigSnippet';
import { useModuleInfo, useSystemModules } from '../hooks/useModules';
import { useT } from '../i18n';
import { useModuleName } from '../i18n/modules';
import { envSnippet, yamlSnippet } from '../utils/moduleSnippets';
import type { ModuleName } from '../types';

/** Shown instead of a page whose module the backend was started without. */
export function ModuleDisabled({ module }: { module: ModuleName }) {
  const t = useT();
  const moduleName = useModuleName();
  const info = useModuleInfo(module);
  const { data } = useSystemModules();
  const property = info?.property ?? `automation.modules.${module}`;
  // With automation.modules.ui-editable the settings switch is the quicker way — except for
  // hardening and startup-only modules, which only the startup configuration decides
  const switchable = !!data?.uiEditable && info?.kind !== 'hardening' && !info?.startupOnly;

  return (
    <EmptyState
      icon={<PackageX className="w-8 h-8 text-text-tertiary" />}
      title={t.modules.disabledPageTitle.replace('{name}', moduleName(module))}
      description={t.modules.disabledPageText}
      action={
        <div className="text-left space-y-3 max-w-md">
          {switchable && <p className="text-sm text-text" data-testid="module-disabled-ui-hint">{t.modules.disabledPageUiHint}</p>}
          <p className="text-sm text-text-secondary">{t.modules.disabledPageHowTo}</p>
          <ConfigSnippet label={t.modules.yaml} text={yamlSnippet(property, true)} />
          {info?.envVar && <ConfigSnippet label={t.modules.env} text={envSnippet(info.envVar, true)} />}
          <Link to="/settings#modules" className="inline-block text-sm text-accent hover:underline">
            {t.modules.disabledPageSettingsLink}
          </Link>
        </div>
      }
    />
  );
}
