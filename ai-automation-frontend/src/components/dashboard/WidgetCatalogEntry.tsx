import { Button } from '../ui';
import { useT } from '../../i18n';
import type { WidgetConfig } from './dashboardLayout';
import type { DashboardWidgetDef } from './widgetDefinition';

interface WidgetCatalogEntryProps {
  def: DashboardWidgetDef;
  /** Shown instances of this widget in the view. */
  shownCount: number;
  /** Hidden instances of this widget in the view, in layout order. */
  hiddenInstanceIds: readonly string[];
  /** False at the widget limit of the view: nothing can be appended, hidden widgets can still be shown. */
  canAppend: boolean;
  disabled: boolean;
  /** This entry's config form is open (multi widgets with a configForm). */
  configuring: boolean;
  onConfigure: (widgetId: string | null) => void;
  onAdd: (widgetId: string, config?: WidgetConfig) => void;
  onShow: (instanceId: string) => void;
}

/** One widget in the catalog: label, description and what "add" means for it right now. All plain text. */
export function WidgetCatalogEntry({
  def, shownCount, hiddenInstanceIds, canAppend, disabled, configuring, onConfigure, onAdd, onShow,
}: WidgetCatalogEntryProps) {
  const t = useT();
  const label = def.label(t);
  const ConfigForm = def.configForm;
  const hiddenSingleton = !def.multiple ? hiddenInstanceIds[0] : undefined;

  const action = !def.multiple && shownCount > 0 ? (
    <Button variant="ghost" size="sm" disabled aria-label={`${t.dashboard.catalog.inView}: ${label}`}>
      {t.dashboard.catalog.inView}
    </Button>
  ) : hiddenSingleton ? (
    <Button variant="secondary" size="sm" disabled={disabled} onClick={() => onShow(hiddenSingleton)} aria-label={`${t.dashboard.showWidget}: ${label}`}>
      {t.dashboard.showWidget}
    </Button>
  ) : (
    <Button
      variant="secondary"
      size="sm"
      disabled={disabled || !canAppend || configuring}
      onClick={() => (ConfigForm ? onConfigure(def.id) : onAdd(def.id))}
      aria-label={`${t.dashboard.catalog.add}: ${label}`}
    >
      {t.dashboard.catalog.add}
    </Button>
  );

  const instances = shownCount + hiddenInstanceIds.length;

  return (
    <li className="py-2.5">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-medium text-text">{label}</p>
          <p className="text-xs text-text-secondary">{def.description(t)}</p>
          {def.multiple && instances > 0 && (
            <p className="text-xs text-text-tertiary">{t.dashboard.catalog.instances.replace('{count}', () => String(instances))}</p>
          )}
        </div>
        <div className="shrink-0">{action}</div>
      </div>
      {/* Hidden instances of a multi widget, shown again one by one (instances have no names of their own yet). */}
      {def.multiple && hiddenInstanceIds.length > 0 && (
        <div className="mt-1 flex flex-wrap gap-1">
          {hiddenInstanceIds.map((instanceId, index) => {
            const text = t.dashboard.catalog.showInstance.replace('{n}', () => String(index + 1));
            return (
              <Button key={instanceId} variant="ghost" size="sm" disabled={disabled} onClick={() => onShow(instanceId)} aria-label={`${text}: ${label}`}>
                {text}
              </Button>
            );
          })}
        </div>
      )}
      {configuring && ConfigForm && !disabled && (
        <div className="mt-2 rounded-lg border border-border bg-surface p-3">
          <ConfigForm
            onSubmit={(config) => {
              onAdd(def.id, config);
              onConfigure(null);
            }}
            onCancel={() => onConfigure(null)}
          />
        </div>
      )}
    </li>
  );
}
