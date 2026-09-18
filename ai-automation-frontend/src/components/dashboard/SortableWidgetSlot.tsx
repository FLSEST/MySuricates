import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { EyeOff, GripVertical } from 'lucide-react';
import { useT } from '../../i18n';
import type { ResolvedWidget } from '../../hooks/useDashboardLayout';
import { WidgetContent } from './WidgetContent';
import { SLOT_CLASSES, widgetSizeClasses } from './widgetSlot';

interface SortableWidgetSlotProps {
  widget: ResolvedWidget;
  disabled: boolean;
  onHide: (instanceId: string) => void;
}

/** A widget in edit mode: inert content, dashed frame, toolbar with drag handle and hide. */
export function SortableWidgetSlot({ widget, disabled, onHide }: SortableWidgetSlotProps) {
  const t = useT();
  const { def, instance } = widget;
  const label = def.label(t);
  const { attributes, listeners, setNodeRef, transform, transition, isDragging, isOver } = useSortable({ id: instance.instanceId, disabled });
  const frame = isDragging
    ? 'z-20 outline-dashed outline-accent opacity-80 shadow-lg'
    : isOver
      ? 'outline-solid outline-accent bg-accent/5'
      : 'outline-dashed outline-accent/40';

  return (
    <div
      ref={setNodeRef}
      data-widget-id={instance.instanceId}
      data-widget-type={def.id}
      data-drop-target={isOver && !isDragging ? 'true' : undefined}
      // Translate, not Transform: slots differ in size and must not be scaled while sorting.
      style={{ transform: CSS.Translate.toString(transform), transition }}
      className={`${SLOT_CLASSES} ${widgetSizeClasses(widget)} relative rounded-xl outline-2 outline-offset-2 ${frame}`}
    >
      {/* Content stays visible but inert — no navigation while arranging. */}
      <div className="peer grid grid-cols-1 select-none" inert>
        <WidgetContent widget={widget} />
      </div>
      <div className="hidden peer-empty:flex min-h-20 flex-col justify-center rounded-xl border border-card-border bg-surface-raised px-4 py-3">
        <p className="text-sm font-medium text-text-secondary truncate">{label}</p>
        <p className="text-xs text-text-tertiary">{t.dashboard.widgetNoData}</p>
      </div>
      <div className="absolute -top-3 right-2 z-10 flex items-center gap-0.5 rounded-lg border border-border bg-surface-overlay p-0.5 shadow-sm">
        <button
          type="button"
          {...attributes}
          {...listeners}
          disabled={disabled}
          className="cursor-grab touch-none rounded-md p-1 text-text-secondary hover:bg-surface-hover hover:text-text active:cursor-grabbing disabled:cursor-not-allowed disabled:opacity-50"
          aria-label={`${t.dashboard.moveWidget}: ${label}`}
          title={t.dashboard.moveWidget}
        >
          <GripVertical className="w-3.5 h-3.5" />
        </button>
        <button
          type="button"
          onClick={() => onHide(instance.instanceId)}
          disabled={disabled}
          className="rounded-md p-1 text-text-secondary hover:bg-surface-hover hover:text-danger disabled:cursor-not-allowed disabled:opacity-50"
          aria-label={`${t.dashboard.hideWidget}: ${label}`}
          title={t.dashboard.hideWidget}
        >
          <EyeOff className="w-3.5 h-3.5" />
        </button>
      </div>
    </div>
  );
}
