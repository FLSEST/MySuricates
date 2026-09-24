import { PackageX } from 'lucide-react';
import { useT } from '../../i18n';
import { useModuleName } from '../../i18n/modules';
import { useSystemModules } from '../../hooks/useModules';
import type { ModuleName } from '../../types';

/**
 * The toggle under a template list whose hidden entries need a module this instance has switched
 * off (TODO 560d18dd): hidden by default, so a new user sees what runs here — shown on request.
 */
export function NeedsModuleToggle({ count, shown, onToggle }: { count: number; shown: boolean; onToggle: () => void }) {
  const t = useT();
  if (count === 0) return null;
  const label = shown
    ? t.templates.hideNeedsModule
    : (count === 1 ? t.templates.showNeedsModuleOne : t.templates.showNeedsModule.replace('{count}', String(count)));
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-expanded={shown}
      data-testid="needs-module-toggle"
      className="text-xs font-medium text-accent hover:underline"
    >
      {label}
    </button>
  );
}

/** On a card shown despite a missing module: which module, and how it is switched on. */
export function TemplateModuleNote({ missing }: { missing: ModuleName[] }) {
  const t = useT();
  const moduleName = useModuleName();
  const { data } = useSystemModules();
  if (missing.length === 0) return null;
  const properties = missing.map((name) =>
    `${data?.modules.find((m) => m.name === name)?.property ?? `automation.modules.${name}`}: true`);
  return (
    <div
      data-testid="template-module-note"
      className="mt-2 space-y-1 rounded-md border border-warning/30 bg-warning-subtle px-2.5 py-2 text-xs text-text-secondary"
    >
      <p className="flex items-start gap-1.5 text-text">
        <PackageX className="mt-0.5 h-3.5 w-3.5 shrink-0 text-warning" aria-hidden="true" />
        {t.templates.needsModule.replace('{names}', missing.map(moduleName).join(', '))}
      </p>
      <p>{data?.uiEditable ? t.templates.enableModuleUi : t.templates.enableModuleConfig}</p>
      <pre className="whitespace-pre-wrap break-all font-mono text-[11px] text-text">{properties.join('\n')}</pre>
    </div>
  );
}
