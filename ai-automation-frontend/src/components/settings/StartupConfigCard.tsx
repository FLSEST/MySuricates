import { Info, ShieldAlert } from 'lucide-react';
import { Card, Badge } from '../ui';
import { ConfigSnippet } from './ConfigSnippet';
import { ContainerAccessSection } from './ContainerAccessSection';
import { useSystemModules } from '../../hooks/useModules';
import { useT } from '../../i18n';
import { useModuleName } from '../../i18n/modules';
import type { ModuleInfo } from '../../types';

// --force, because on a running instance config/application.yml always already exists: without
// the flag setup.sh writes nothing there (729ceb19).
const SETUP_COMMAND = './scripts/setup.sh --preset <core|research|full> --force';

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
        <ContainerAccessSection />

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

        <div className="max-w-md space-y-1.5">
          <ConfigSnippet label={t.startupConfig.setupCommand} text={SETUP_COMMAND} />
          {/* module_preset_write refuses to overwrite an existing config — say so, or the command reads as a no-op */}
          <p className="flex items-start gap-2 text-xs text-text-tertiary" data-testid="startup-config-setup-hint">
            <Info className="w-3.5 h-3.5 mt-0.5 shrink-0" />
            {t.startupConfig.setupCommandHint}
          </p>
        </div>
      </div>
    </Card>
  );
}

function ModuleStateList({ title, modules }: { title: string; modules: ModuleInfo[] }) {
  const t = useT();
  const moduleName = useModuleName();
  if (modules.length === 0) return null;
  return (
    <div>
      <h4 className="text-sm font-semibold text-text mb-2">{title}</h4>
      <ul className="space-y-1.5">
        {modules.map((m) => {
          // This card shows what the instance STARTED with: `configured` ignores a settings-UI
          // switch (ModulesCard shows the runtime state). Older backends send no `configured`.
          const configured = m.configured ?? m.enabled;
          return (
            <li
              key={m.name}
              data-testid={`startup-config-module-${m.name}`}
              className="flex items-center gap-2 text-sm"
            >
              <span
                className={`w-2 h-2 rounded-full shrink-0 ${configured ? 'bg-success' : 'bg-text-tertiary'}`}
                aria-hidden="true"
              />
              <span className="text-text">{moduleName(m.name)}</span>
              <span className="text-xs text-text-tertiary">{configured ? t.modules.enabled : t.modules.disabled}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
