import { useState } from 'react';
import { ChevronRight, Lock } from 'lucide-react';
import { Card, CardHeader, Badge } from '../ui';
import type { BadgeVariant } from '../ui/Badge';
import { ConfigSnippet } from './ConfigSnippet';
import { useSystemModules } from '../../hooks/useModules';
import { useT } from '../../i18n';
import { envSnippet, yamlSnippet } from '../../utils/moduleSnippets';
import type { ModuleInfo, ModuleMaturity } from '../../types';

const MATURITY_VARIANT: Record<ModuleMaturity, BadgeVariant> = {
  stable: 'success',
  experimental: 'warning',
  legacy: 'default',
};

/**
 * Read-only overview of optional modules and hardening switches. Deliberately without
 * toggles: modules are decided at backend startup so the API cannot re-enable them.
 */
export function ModulesCard() {
  const t = useT();
  const { data } = useSystemModules();
  const modules = data?.modules ?? [];
  const features = modules.filter((m) => m.kind === 'feature');
  const hardening = modules.filter((m) => m.kind === 'hardening');

  return (
    <Card>
      <CardHeader title={t.modules.title} subtitle={t.modules.subtitle} />
      <div className="space-y-5">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <span className="text-text-secondary">{t.modules.preset}:</span>
          {data && <Badge variant="accent">{t.modules.presets[data.preset]}</Badge>}
        </div>
        <p className="flex items-start gap-2 text-xs text-text-tertiary">
          <Lock className="w-3.5 h-3.5 mt-0.5 shrink-0" />
          {t.modules.readOnlyHint}
        </p>

        <ModuleGroup title={t.modules.featureGroup} modules={features} />
        <ModuleGroup title={t.modules.hardeningGroup} modules={hardening} />
      </div>
    </Card>
  );
}

function ModuleGroup({ title, modules }: { title: string; modules: ModuleInfo[] }) {
  if (modules.length === 0) return null;
  return (
    <div>
      <h4 className="text-sm font-semibold text-text mb-2">{title}</h4>
      <ul className="divide-y divide-border rounded-lg border border-border">
        {modules.map((m) => <ModuleRow key={m.name} module={m} />)}
      </ul>
    </div>
  );
}

function ModuleRow({ module: m }: { module: ModuleInfo }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const target = !m.enabled;

  return (
    <li data-testid={`module-${m.name}`}>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="flex w-full flex-wrap items-center gap-2 px-3 py-2.5 text-left hover:bg-surface transition-colors"
      >
        <ChevronRight className={`w-3.5 h-3.5 text-text-tertiary transition-transform ${open ? 'rotate-90' : ''}`} />
        <span
          className={`w-2 h-2 rounded-full shrink-0 ${m.enabled ? 'bg-success' : 'bg-text-tertiary'}`}
          aria-label={m.enabled ? t.modules.enabled : t.modules.disabled}
        />
        <span className="text-sm font-medium text-text">{t.modules.names[m.name]}</span>
        <span className="text-xs text-text-tertiary">{m.enabled ? t.modules.enabled : t.modules.disabled}</span>
        <span className="ml-auto flex flex-wrap gap-1.5">
          {m.kind === 'feature' && <Badge>{t.modules.tier[m.tier]}</Badge>}
          <Badge variant={MATURITY_VARIANT[m.maturity]}>{t.modules.maturity[m.maturity]}</Badge>
          {m.risks.map((r) => <Badge key={r} variant="danger">{t.modules.risk[r]}</Badge>)}
        </span>
      </button>
      {open && (
        <div className="space-y-3 px-9 pb-3">
          <p className="text-xs text-text-secondary">{m.description}</p>
          {m.requires.length > 0 && (
            <p className="text-xs text-text-secondary">
              {t.modules.requires}: {m.requires.map((r) => t.modules.names[r]).join(', ')}
            </p>
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
