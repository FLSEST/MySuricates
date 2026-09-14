import { Link } from 'react-router-dom';
import { PackageX } from 'lucide-react';
import { EmptyState } from '../components/shared/EmptyState';
import { ConfigSnippet } from '../components/settings/ConfigSnippet';
import { useModuleInfo } from '../hooks/useModules';
import { useT } from '../i18n';
import { envSnippet, yamlSnippet } from '../utils/moduleSnippets';
import type { ModuleName } from '../types';

/** Shown instead of a page whose module the backend was started without. */
export function ModuleDisabled({ module }: { module: ModuleName }) {
  const t = useT();
  const info = useModuleInfo(module);
  const property = info?.property ?? `automation.modules.${module}`;

  return (
    <EmptyState
      icon={<PackageX className="w-8 h-8 text-text-tertiary" />}
      title={t.modules.disabledPageTitle.replace('{name}', t.modules.names[module])}
      description={t.modules.disabledPageText}
      action={
        <div className="text-left space-y-3 max-w-md">
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
