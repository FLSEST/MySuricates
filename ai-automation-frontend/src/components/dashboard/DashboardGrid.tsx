import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import { SortableContext, sortableKeyboardCoordinates } from '@dnd-kit/sortable';
import type { ResolvedWidget } from '../../hooks/useDashboardLayout';
import type { WidgetSize } from './dashboardLayout';
import { staticSortingStrategy, widgetCollisionDetection } from './dashboardDnd';
import { SortableWidgetSlot } from './SortableWidgetSlot';
import { WidgetContent } from './WidgetContent';
import { SLOT_CLASSES, widgetSizeClasses } from './widgetSlot';

interface DashboardGridProps {
  widgets: ResolvedWidget[];
  editMode: boolean;
  /** Locks arranging, resizing, hiding and removing (e.g. while a server version is applied). */
  disabled?: boolean;
  onMove: (activeInstanceId: string, overInstanceId: string) => void;
  onHide: (instanceId: string) => void;
  onResize: (instanceId: string, size: WidgetSize) => void;
  /** Offered for catalog-only and multi widgets only. */
  onRemove: (instanceId: string) => void;
}

export function DashboardGrid({ widgets, editMode, disabled = false, onMove, onHide, onResize, onRemove }: DashboardGridProps) {
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
            <SortableWidgetSlot
              key={widget.instance.instanceId}
              widget={widget}
              disabled={disabled}
              onHide={onHide}
              onResize={onResize}
              onRemove={onRemove}
            />
          ))}
        </div>
      </SortableContext>
    </DndContext>
  );
}
