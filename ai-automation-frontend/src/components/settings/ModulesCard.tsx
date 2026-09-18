import { useState } from 'react';
import { ChevronRight, Lock, ShieldAlert, RotateCcw } from 'lucide-react';
import { Card, Badge, Button, Toggle } from '../ui';
import type { BadgeVariant } from '../ui/Badge';
import { ConfigSnippet } from './ConfigSnippet';
import { useResetModule, useSetModuleEnabled, useSystemModules } from '../../hooks/useModules';
import { useT } from '../../i18n';
import { useModuleName, useModuleText } from '../../i18n/modules';
import { envSnippet, yamlSnippet } from '../../utils/moduleSnippets';
import type { ModuleInfo, ModuleMaturity } from '../../types';

const MATURITY_VARIANT: Record<ModuleMaturity, BadgeVariant> = {
  stable: 'success',
  experimental: 'warning',
  legacy: 'default',
};

const UI_EDITABLE_PROPERTY = 'automation.modules.ui-editable';

/**
 * Optional modules and hardening switches. Read-only unless the backend runs with
 * automation.modules.ui-editable — then feature modules get a switch (effective immediately).
 * Hardening is never switchable here.
 */
export function ModulesCard() {
  const t = useT();
  const { data } = useSystemModules();
  const modules = data?.modules ?? [];
  const features = modules.filter((m) => m.kind === 'feature');
  const hardening = modules.filter((m) => m.kind === 'hardening');
  const editable = !!data?.uiEditable;

  return (
    <Card>
      {/* The settings section already carries the title */}
      <p className="text-sm text-text-secondary mb-4">{t.modules.subtitle}</p>
      <div className="space-y-5">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <span className="text-text-secondary">{t.modules.preset}:</span>
          {data && <Badge variant="accent">{t.modules.presets[data.preset]}</Badge>}
        </div>
        {editable ? (
          <p className="flex items-start gap-2 rounded-lg bg-warning/10 px-3 py-2 text-xs text-warning" data-testid="modules-ui-editable">
            <ShieldAlert className="w-3.5 h-3.5 mt-0.5 shrink-0" />
            {t.modules.uiEditableActive}
          </p>
        ) : (
          <div className="space-y-2">
            <p className="flex items-start gap-2 text-xs text-text-tertiary">
              <Lock className="w-3.5 h-3.5 mt-0.5 shrink-0" />
              {t.modules.readOnlyHint}
            </p>
            <div className="max-w-md">
              <ConfigSnippet label={t.modules.yaml} text={yamlSnippet(UI_EDITABLE_PROPERTY, true)} />
            </div>
            <p className="flex items-start gap-2 text-xs text-warning">
              <ShieldAlert className="w-3.5 h-3.5 mt-0.5 shrink-0" />
              {t.modules.uiEditableRisk}
            </p>
          </div>
        )}

        <ModuleGroup title={t.modules.featureGroup} modules={features} editable={editable} />
        <ModuleGroup title={t.modules.hardeningGroup} modules={hardening} editable={false}
          note={t.modules.hardeningNotEditable} />
      </div>
    </Card>
  );
}

function ModuleGroup({ title, modules, editable, note }: {
  title: string; modules: ModuleInfo[]; editable: boolean; note?: string;
}) {
  if (modules.length === 0) return null;
  return (
    <div>
      <h4 className="text-sm font-semibold text-text mb-2">{title}</h4>
      {note && (
        <p className="flex items-start gap-2 text-xs text-text-tertiary mb-2" data-testid="hardening-note">
          <Lock className="w-3.5 h-3.5 mt-0.5 shrink-0" />
          {note}
        </p>
      )}
      <ul className="divide-y divide-border rounded-lg border border-border">
        {modules.map((m) => <ModuleRow key={m.name} module={m} editable={editable} />)}
      </ul>
    </div>
  );
}

function ModuleRow({ module: m, editable }: { module: ModuleInfo; editable: boolean }) {
  const t = useT();
  const moduleName = useModuleName();
  const moduleTextOf = useModuleText();
  const [open, setOpen] = useState(false);
  const setEnabled = useSetModuleEnabled();
  const reset = useResetModule();
  const target = !m.enabled;
  const configured = m.configured ?? m.enabled;
  const onOff = (value: boolean) => (value ? t.modules.enabled : t.modules.disabled);

  return (
    <li data-testid={`module-${m.name}`}>
      <div className="flex items-center gap-2 pr-3">
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          aria-expanded={open}
          className="flex min-w-0 flex-1 flex-wrap items-center gap-2 px-3 py-2.5 text-left hover:bg-surface transition-colors"
        >
          <ChevronRight className={`w-3.5 h-3.5 text-text-tertiary transition-transform ${open ? 'rotate-90' : ''}`} />
          <span
            className={`w-2 h-2 rounded-full shrink-0 ${m.enabled ? 'bg-success' : 'bg-text-tertiary'}`}
            aria-label={onOff(m.enabled)}
          />
          <span className="text-sm font-medium text-text">{moduleName(m.name)}</span>
          <span className="text-xs text-text-tertiary">{onOff(m.enabled)}</span>
          {m.overridden && <Badge variant="warning">{t.modules.overridden}</Badge>}
          <span className="ml-auto flex flex-wrap gap-1.5">
            {m.kind === 'feature' && <Badge>{t.modules.tier[m.tier]}</Badge>}
            <Badge variant={MATURITY_VARIANT[m.maturity]}>{t.modules.maturity[m.maturity]}</Badge>
            {m.risks.map((r) => <Badge key={r} variant="danger">{t.modules.risk[r]}</Badge>)}
          </span>
        </button>
        {editable && (
          <Toggle
            enabled={m.enabled}
            ariaLabel={t.modules.switchLabel.replace('{name}', moduleName(m.name))}
            disabled={setEnabled.isPending || reset.isPending}
            onChange={(enabled) => setEnabled.mutate({ name: m.name, enabled })}
          />
        )}
      </div>
      {open && (
        <div className="space-y-3 px-9 pb-3">
          {/* Localized text; the backend's English description covers a module this build does not know yet */}
          <p className="text-xs text-text-secondary">{moduleTextOf(m.name).description || m.description}</p>
          {m.requires.length > 0 && (
            <p className="text-xs text-text-secondary">
              {t.modules.requires}: {m.requires.map((r) => moduleName(r)).join(', ')}
            </p>
          )}
          {m.overridden && (
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xs text-text-secondary">
                {t.modules.configValue.replace('{value}', onOff(configured))}
              </span>
              <Button size="sm" variant="ghost" icon={<RotateCcw className="w-3.5 h-3.5" />}
                loading={reset.isPending} onClick={() => reset.mutate(m.name)}>
                {t.modules.resetToConfig}
              </Button>
            </div>
          )}
          <p className="text-xs font-medium text-text">{target ? t.modules.enableSnippet : t.modules.disableSnippet}</p>
          <div className="grid gap-3 md:grid-cols-2">
            <ConfigSnippet label={t.modules.yaml} text={yamlSnippet(m.property, target)} />
            <ConfigSnippet label={t.modules.env} text={envSnippet(m.envVar, target)} />
          </div>
        </div>
      )}
    </li>
  );
}
