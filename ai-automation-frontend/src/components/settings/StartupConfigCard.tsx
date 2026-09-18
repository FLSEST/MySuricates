import { ShieldAlert } from 'lucide-react';
import { Card, Badge } from '../ui';
import { ConfigSnippet } from './ConfigSnippet';
import { useSystemModules } from '../../hooks/useModules';
import { useT } from '../../i18n';
import type { ModuleInfo } from '../../types';

const SETUP_COMMAND = './scripts/setup.sh --preset <core|research|full>';

/**
 * Read-only summary of the startup configuration (GET /api/v1/system/capabilities): module
 * preset, active/inactive modules, whether feature modules are switchable via the settings UI
 * (automation.modules.ui-editable) and the four hardening switches (automation.hardening.*).
 * No write path and no first-start detection here — see 4a4708be; switching modules (when
 * ui-editable) lives in {@link "./ModulesCard"}.
 */
export function StartupConfigCard() {
  const t = useT();
  const { data } = useSystemModules();
  const modules = data?.modules ?? [];
  const features = modules.filter((m) => m.kind === 'feature');
  const hardening = modules.filter((m) => m.kind === 'hardening');
  const editable = !!data?.uiEditable;

  return (
    <Card data-testid="startup-config">
      <h3 className="text-sm font-semibold text-text mb-1">{t.startupConfig.title}</h3>
      <p className="text-sm text-text-secondary mb-4">{t.startupConfig.subtitle}</p>
      <div className="space-y-5">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <span className="text-text-secondary">{t.modules.preset}:</span>
          {data && <Badge variant="accent">{t.modules.presets[data.preset]}</Badge>}
        </div>

        <ModuleStateList title={t.modules.featureGroup} modules={features} />
        <ModuleStateList title={t.modules.hardeningGroup} modules={hardening} />

        <div className="flex flex-wrap items-center gap-2 text-sm">
          <span className="text-text-secondary">{t.startupConfig.uiEditableLabel}:</span>
          <span data-testid="startup-config-ui-editable" className="text-text">
            {editable ? t.modules.enabled : t.modules.disabled}
          </span>
        </div>
        {editable && (
          <p
            className="flex items-start gap-2 rounded-lg bg-warning/10 px-3 py-2 text-xs text-warning"
            data-testid="startup-config-ui-editable-risk"
          >
            <ShieldAlert className="w-3.5 h-3.5 mt-0.5 shrink-0" />
            {t.startupConfig.uiEditableRisk}
          </p>
        )}

        <div className="max-w-md">
          <ConfigSnippet label={t.startupConfig.setupCommand} text={SETUP_COMMAND} />
        </div>
      </div>
    </Card>
  );
}

function ModuleStateList({ title, modules }: { title: string; modules: ModuleInfo[] }) {
  const t = useT();
  if (modules.length === 0) return null;
  return (
    <div>
      <h4 className="text-sm font-semibold text-text mb-2">{title}</h4>
      <ul className="space-y-1.5">
        {modules.map((m) => (
          <li
            key={m.name}
            data-testid={`startup-config-module-${m.name}`}
            className="flex items-center gap-2 text-sm"
          >
            <span
              className={`w-2 h-2 rounded-full shrink-0 ${m.enabled ? 'bg-success' : 'bg-text-tertiary'}`}
              aria-hidden="true"
            />
            <span className="text-text">{t.modules.names[m.name]}</span>
            <span className="text-xs text-text-tertiary">{m.enabled ? t.modules.enabled : t.modules.disabled}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
