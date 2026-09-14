import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext,
  rectSortingStrategy,
  sortableKeyboardCoordinates,
  useSortable,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { EyeOff, GripVertical, Plus } from 'lucide-react';
import { useT } from '../../i18n';
import type { ResolvedWidget } from '../../hooks/useDashboardLayout';
import type { WidgetConfig } from './dashboardLayout';
import { WidgetFrame } from './WidgetFrame';
import { WIDGET_SIZE_CLASSES } from './widgetRegistry';

interface DashboardGridProps {
  widgets: ResolvedWidget[];
  editMode: boolean;
  onMove: (activeInstanceId: string, overInstanceId: string) => void;
  onHide: (instanceId: string) => void;
}

// grid + minmax(0,1fr) lets the card fill the slot in both axes without overflowing (charts).
const SLOT_CLASSES = 'grid grid-cols-1 grow min-w-0';

const NO_CONFIG: WidgetConfig = Object.freeze({});

function widgetSizeClasses({ def, instance }: ResolvedWidget): string {
  return WIDGET_SIZE_CLASSES[instance.size ?? def.size];
}

/** The widget inside its error boundary. Renders no DOM of its own, so empty widgets stay empty. */
function WidgetContent({ widget }: { widget: ResolvedWidget }) {
  const t = useT();
  const Widget = widget.def.component;
  return (
    <WidgetFrame label={widget.def.label(t)}>
      <Widget instanceId={widget.instance.instanceId} config={widget.instance.config ?? NO_CONFIG} />
    </WidgetFrame>
  );
}

export function DashboardGrid({ widgets, editMode, onMove, onHide }: DashboardGridProps) {
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
    if (over && active.id !== over.id) onMove(String(active.id), String(over.id));
  };

  return (
    <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
      <SortableContext items={widgets.map((w) => w.instance.instanceId)} strategy={rectSortingStrategy}>
        <div className="flex flex-wrap gap-4 pt-3">
          {widgets.map((widget) => (
            <SortableWidget key={widget.instance.instanceId} widget={widget} onHide={onHide} />
          ))}
        </div>
      </SortableContext>
    </DndContext>
  );
}

function SortableWidget({ widget, onHide }: { widget: ResolvedWidget; onHide: (instanceId: string) => void }) {
  const t = useT();
  const { def, instance } = widget;
  const label = def.label(t);
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: instance.instanceId });

  return (
    <div
      ref={setNodeRef}
      data-widget-id={instance.instanceId}
      data-widget-type={def.id}
      // Translate, not Transform: slots differ in size and must not be scaled while sorting.
      style={{ transform: CSS.Translate.toString(transform), transition }}
      className={`${SLOT_CLASSES} ${widgetSizeClasses(widget)} relative rounded-xl outline-2 outline-dashed outline-offset-2 ${
        isDragging ? 'z-20 outline-accent opacity-80 shadow-lg' : 'outline-accent/40'
      }`}
    >
      {/* Content stays visible but inert — no navigation while arranging. */}
      <div className="peer grid grid-cols-1 select-none" inert>
        <WidgetContent widget={widget} />
      </div>
      <div className="hidden peer-empty:flex min-h-20 flex-col justify-center rounded-xl border border-border bg-surface-raised px-4 py-3">
        <p className="text-sm font-medium text-text-secondary truncate">{label}</p>
        <p className="text-xs text-text-tertiary">{t.dashboard.widgetNoData}</p>
      </div>
      <div className="absolute -top-3 right-2 z-10 flex items-center gap-0.5 rounded-lg border border-border bg-surface-overlay p-0.5 shadow-sm">
        <button
          type="button"
          {...attributes}
          {...listeners}
          className="cursor-grab touch-none rounded-md p-1 text-text-secondary hover:bg-surface-hover hover:text-text active:cursor-grabbing"
          aria-label={`${t.dashboard.moveWidget}: ${label}`}
          title={t.dashboard.moveWidget}
        >
          <GripVertical className="w-3.5 h-3.5" />
        </button>
        <button
          type="button"
          onClick={() => onHide(instance.instanceId)}
          className="rounded-md p-1 text-text-secondary hover:bg-surface-hover hover:text-danger"
          aria-label={`${t.dashboard.hideWidget}: ${label}`}
          title={t.dashboard.hideWidget}
        >
          <EyeOff className="w-3.5 h-3.5" />
        </button>
      </div>
    </div>
  );
}

export function HiddenWidgetsBar({ widgets, onShow }: { widgets: ResolvedWidget[]; onShow: (instanceId: string) => void }) {
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
                className="inline-flex items-center gap-1 rounded-full border border-border bg-surface-raised px-2.5 py-1 text-xs text-text-secondary transition-colors hover:border-accent hover:text-text"
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
