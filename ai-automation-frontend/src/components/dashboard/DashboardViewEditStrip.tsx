import { useRef, useState } from 'react';
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
  horizontalListSortingStrategy,
  sortableKeyboardCoordinates,
  useSortable,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { GripVertical } from 'lucide-react';
import { useT } from '../../i18n';
import { LAYOUT_LIMITS, truncateCodePoints, type DashboardView } from './dashboardLayout';
import { useScrollIntoViewWhenActive } from './viewTabA11y';

interface RenameFieldProps {
  initialName: string;
  label: string;
  /** Shown while the field is empty, so a freshly added, still-unnamed view never looks like a blank box. */
  placeholder: string;
  onCommit: (name: string) => void;
  onCancel: () => void;
}

/** Inline name field: Enter or leaving the field saves, Escape cancels. Cuts by code points while typing. */
function RenameField({ initialName, label, placeholder, onCommit, onCancel }: RenameFieldProps) {
  const [draft, setDraft] = useState(initialName);
  // Enter/Escape unmount the field, which can fire blur as well — finish exactly once.
  const finished = useRef(false);
  const finish = (commit: boolean) => {
    if (finished.current) return;
    finished.current = true;
    if (commit) onCommit(draft);
    else onCancel();
  };

  return (
    <input
      autoFocus
      aria-label={label}
      placeholder={placeholder}
      value={draft}
      onChange={(e) => setDraft(truncateCodePoints(e.target.value, LAYOUT_LIMITS.nameLength))}
      onKeyDown={(e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          finish(true);
        } else if (e.key === 'Escape') {
          e.preventDefault();
          finish(false);
        }
      }}
      onBlur={() => finish(true)}
      className="input-base w-40 shrink-0 py-1 text-sm"
    />
  );
}

interface SortableViewProps {
  view: DashboardView;
  label: string;
  active: boolean;
  disabled: boolean;
  renaming: boolean;
  onSelect: (viewId: string) => void;
  onRename: (name: string) => void;
  onCancelRename: () => void;
}

function SortableView({ view, label, active, disabled, renaming, onSelect, onRename, onCancelRename }: SortableViewProps) {
  const t = useT();
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: view.id, disabled });
  const selectRef = useScrollIntoViewWhenActive<HTMLButtonElement>(active);

  return (
    <div
      ref={setNodeRef}
      role="listitem"
      style={{ transform: CSS.Translate.toString(transform), transition }}
      className={`flex shrink-0 items-center gap-0.5 rounded-lg border border-dashed p-0.5 ${
        isDragging ? 'z-10 border-accent bg-surface-overlay shadow-sm' : 'border-accent/40'
      }`}
    >
      <button
        type="button"
        {...attributes}
        {...listeners}
        disabled={disabled}
        className="cursor-grab touch-none rounded-md p-1 text-text-tertiary hover:bg-surface-hover hover:text-text active:cursor-grabbing disabled:cursor-not-allowed disabled:opacity-50"
        aria-label={`${t.dashboard.views.move}: ${label}`}
        title={t.dashboard.views.move}
      >
        <GripVertical className="h-3.5 w-3.5" />
      </button>
      {renaming ? (
        <RenameField
          initialName={view.name}
          label={t.dashboard.views.nameLabel}
          placeholder={t.dashboard.views.defaultName}
          onCommit={onRename}
          onCancel={onCancelRename}
        />
      ) : (
        <button
          ref={selectRef}
          type="button"
          aria-current={active ? 'true' : undefined}
          data-view-id={view.id}
          onClick={() => onSelect(view.id)}
          className={`max-w-48 shrink-0 truncate rounded-lg px-3 py-1.5 text-sm font-medium transition-colors ${
            active ? 'bg-accent text-text-inverse' : 'text-text-secondary hover:bg-surface-raised hover:text-text'
          }`}
        >
          {label}
        </button>
      )}
    </div>
  );
}

interface DashboardViewEditStripProps {
  views: DashboardView[];
  activeViewId: string | undefined;
  disabled: boolean;
  renamingId: string | null;
  labelOf: (view: DashboardView) => string;
  onSelect: (viewId: string) => void;
  onMove: (draggedViewId: string, overViewId: string) => void;
  onRename: (viewId: string, name: string) => void;
  onCancelRename: () => void;
}

/**
 * The views in edit mode: a sortable list, not a tab list — each entry carries a drag handle next to
 * its select button, which a `tablist` must not contain. The shown view is marked `aria-current`.
 */
export function DashboardViewEditStrip({
  views, activeViewId, disabled, renamingId, labelOf, onSelect, onMove, onRename, onCancelRename,
}: DashboardViewEditStripProps) {
  const t = useT();
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const handleDragEnd = ({ active, over }: DragEndEvent) => {
    if (!disabled && over && active.id !== over.id) onMove(String(active.id), String(over.id));
  };

  return (
    <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
      <SortableContext items={views.map((v) => v.id)} strategy={horizontalListSortingStrategy}>
        <div role="list" aria-label={t.dashboard.views.label} className="flex min-w-0 flex-1 items-center gap-1 overflow-x-auto py-1">
          {views.map((view) => (
            <SortableView
              key={view.id}
              view={view}
              label={labelOf(view)}
              active={view.id === activeViewId}
              disabled={disabled}
              renaming={!disabled && renamingId === view.id}
              onSelect={onSelect}
              onRename={(name) => onRename(view.id, name)}
              onCancelRename={onCancelRename}
            />
          ))}
        </div>
      </SortableContext>
    </DndContext>
  );
}
