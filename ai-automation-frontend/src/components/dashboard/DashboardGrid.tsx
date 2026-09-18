import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import { SortableContext, sortableKeyboardCoordinates } from '@dnd-kit/sortable';
import { Plus } from 'lucide-react';
import { useT } from '../../i18n';
import type { ResolvedWidget } from '../../hooks/useDashboardLayout';
import { staticSortingStrategy, widgetCollisionDetection } from './dashboardDnd';
import { SortableWidgetSlot } from './SortableWidgetSlot';
import { WidgetContent } from './WidgetContent';
import { SLOT_CLASSES, widgetSizeClasses } from './widgetSlot';

interface DashboardGridProps {
  widgets: ResolvedWidget[];
  editMode: boolean;
  /** Locks arranging and hiding (e.g. while a server version is applied). */
  disabled?: boolean;
  onMove: (activeInstanceId: string, overInstanceId: string) => void;
  onHide: (instanceId: string) => void;
}

export function DashboardGrid({ widgets, editMode, disabled = false, onMove, onHide }: DashboardGridProps) {
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  if (!editMode) {
    return (
      <div className="flex flex-wrap gap-4">
        {widgets.map((widget) => (
          // empty:hidden collapses widgets that render nothing (no data yet).
          <div
            key={widget.instance.instanceId}
            data-widget-id={widget.instance.instanceId}
            data-widget-type={widget.def.id}
            className={`${SLOT_CLASSES} ${widgetSizeClasses(widget)} empty:hidden`}
          >
            <WidgetContent widget={widget} />
          </div>
        ))}
      </div>
    );
  }

  const handleDragEnd = ({ active, over }: DragEndEvent) => {
    if (!disabled && over && active.id !== over.id) onMove(String(active.id), String(over.id));
  };

  return (
    <DndContext sensors={sensors} collisionDetection={widgetCollisionDetection} onDragEnd={handleDragEnd}>
      <SortableContext items={widgets.map((w) => w.instance.instanceId)} strategy={staticSortingStrategy}>
        <div className="flex flex-wrap gap-4 pt-3">
          {widgets.map((widget) => (
            <SortableWidgetSlot key={widget.instance.instanceId} widget={widget} disabled={disabled} onHide={onHide} />
          ))}
        </div>
      </SortableContext>
    </DndContext>
  );
}

export function HiddenWidgetsBar({ widgets, disabled = false, onShow }: {
  widgets: ResolvedWidget[];
  /** Locks showing widgets again (e.g. while a server version is applied). */
  disabled?: boolean;
  onShow: (instanceId: string) => void;
}) {
  const t = useT();
  return (
    <div className="rounded-xl border border-dashed border-accent/40 bg-accent/5 px-4 py-3 space-y-2">
      <p className="text-sm text-text-secondary">{t.dashboard.editHint}</p>
      {widgets.length > 0 && (
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs text-text-tertiary">{t.dashboard.hiddenWidgets}</span>
          {widgets.map(({ def, instance }) => {
            const label = def.label(t);
            return (
              <button
                key={instance.instanceId}
                type="button"
                onClick={() => onShow(instance.instanceId)}
                disabled={disabled}
                className="inline-flex items-center gap-1 rounded-full border border-border bg-surface-raised px-2.5 py-1 text-xs text-text-secondary transition-colors hover:border-accent hover:text-text disabled:cursor-not-allowed disabled:opacity-50"
                aria-label={`${t.dashboard.showWidget}: ${label}`}
              >
                <Plus className="w-3 h-3" />
                {label}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
