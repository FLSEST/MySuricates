import { useState } from 'react';
import { Modal } from '../ui';
import { useT } from '../../i18n';
import type { ResolvedWidget } from '../../hooks/useDashboardLayout';
import { LAYOUT_LIMITS, type WidgetConfig } from './dashboardLayout';
import { WIDGET_CATEGORIES, type DashboardWidgetDef } from './widgetDefinition';
import { WidgetCatalogEntry } from './WidgetCatalogEntry';

interface WidgetCatalogDialogProps {
  open: boolean;
  onClose: () => void;
  /** Widgets of enabled modules in registry order. */
  available: readonly DashboardWidgetDef[];
  visible: readonly ResolvedWidget[];
  hidden: readonly ResolvedWidget[];
  canAppend: boolean;
  /** Locks adding (e.g. while a server version is applied). */
  disabled: boolean;
  onAdd: (widgetId: string, config?: WidgetConfig) => void;
  onShow: (instanceId: string) => void;
}

function instancesOf(widgets: readonly ResolvedWidget[], widgetId: string): string[] {
  return widgets.filter((w) => w.def.id === widgetId).map((w) => w.instance.instanceId);
}

/**
 * "Add widget" (spec E4): every widget of the enabled modules by category, with a description. It
 * shows hidden widgets again (it replaces the former "+ name" chips), adds catalog widgets, and is
 * the way in for multi widgets and their config form. The dialog stays open, so several widgets can
 * be added in a row.
 */
export function WidgetCatalogDialog({
  open, onClose, available, visible, hidden, canAppend, disabled, onAdd, onShow,
}: WidgetCatalogDialogProps) {
  const t = useT();
  const [configuringId, setConfiguringId] = useState<string | null>(null);

  const close = () => {
    setConfiguringId(null);
    onClose();
  };

  return (
    <Modal open={open} onClose={close} title={t.dashboard.catalog.title} size="lg">
      <div className="space-y-5">
        {!canAppend && (
          <p role="status" className="text-xs text-warning">
            {t.dashboard.catalog.limitReached.replace('{max}', () => String(LAYOUT_LIMITS.widgetsPerView))}
          </p>
        )}
        {WIDGET_CATEGORIES.map((category) => {
          const defs = available.filter((def) => def.category === category);
          if (defs.length === 0) return null;
          const headingId = `widget-catalog-${category}`;
          return (
            <section key={category} aria-labelledby={headingId}>
              <h3 id={headingId} className="text-xs font-semibold uppercase tracking-wider text-text-tertiary">
                {t.dashboard.catalog.categories[category]}
              </h3>
              <ul className="divide-y divide-border">
                {defs.map((def) => (
                  <WidgetCatalogEntry
                    key={def.id}
                    def={def}
                    shownCount={instancesOf(visible, def.id).length}
                    hiddenInstanceIds={instancesOf(hidden, def.id)}
                    canAppend={canAppend}
                    disabled={disabled}
                    configuring={configuringId === def.id}
                    onConfigure={setConfiguringId}
                    onAdd={onAdd}
                    onShow={onShow}
                  />
                ))}
              </ul>
            </section>
          );
        })}
      </div>
    </Modal>
  );
}
