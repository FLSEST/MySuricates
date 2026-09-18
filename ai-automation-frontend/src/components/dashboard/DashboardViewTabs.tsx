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
import { Copy, GripVertical, Pencil, Plus, Trash2 } from 'lucide-react';
import { Button } from '../ui';
import { useT } from '../../i18n';
import { selectActiveView, useDashboardLayoutStore } from '../../stores/dashboardLayoutStore';
import { LAYOUT_LIMITS, truncateCodePoints, type DashboardView } from './dashboardLayout';

interface DashboardViewTabsProps {
  editMode: boolean;
  /** Locks every change to the views (e.g. while a server version is applied); switching stays possible. */
  disabled: boolean;
}

interface TabButtonProps {
  view: DashboardView;
  label: string;
  active: boolean;
  onSelect: (viewId: string) => void;
}

function TabButton({ view, label, active, onSelect }: TabButtonProps) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={active}
      data-view-id={view.id}
      onClick={() => onSelect(view.id)}
      className={`max-w-48 shrink-0 truncate rounded-lg px-3 py-1.5 text-sm font-medium transition-colors ${
        active ? 'bg-accent text-text-inverse' : 'text-text-secondary hover:bg-surface-raised hover:text-text'
      }`}
    >
      {label}
    </button>
  );
}

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

interface SortableTabProps extends TabButtonProps {
  disabled: boolean;
  renaming: boolean;
  onRename: (name: string) => void;
  onCancelRename: () => void;
}

function SortableTab({ view, label, active, disabled, renaming, onSelect, onRename, onCancelRename }: SortableTabProps) {
  const t = useT();
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: view.id, disabled });

  return (
    <div
      ref={setNodeRef}
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
        <TabButton view={view} label={label} active={active} onSelect={onSelect} />
      )}
    </div>
  );
}

/**
 * Tabs above the widget grid, one per view (spec E4). Edit mode adds rename, duplicate, delete,
 * drag reordering and "add view". Names are user text and only ever rendered as React text.
 */
export function DashboardViewTabs({ editMode, disabled }: DashboardViewTabsProps) {
  const t = useT();
  const views = useDashboardLayoutStore((s) => s.views);
  const activeView = useDashboardLayoutStore(selectActiveView);
  const setActiveView = useDashboardLayoutStore((s) => s.setActiveView);
  const addView = useDashboardLayoutStore((s) => s.addView);
  const renameView = useDashboardLayoutStore((s) => s.renameView);
  const duplicateView = useDashboardLayoutStore((s) => s.duplicateView);
  const removeView = useDashboardLayoutStore((s) => s.removeView);
  const moveView = useDashboardLayoutStore((s) => s.moveView);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  // One view outside edit mode: no tab strip, the dashboard looks as it did before views existed.
  if (!editMode && views.length < 2) return null;

  const labelOf = (view: DashboardView) => view.name || t.dashboard.views.defaultName;
  const atLimit = views.length >= LAYOUT_LIMITS.views;
  const limitHint = t.dashboard.views.limitReached.replace('{max}', () => String(LAYOUT_LIMITS.views));
  // Scrolls sideways inside itself, so many or long tabs never widen the page.
  const stripClasses = 'flex min-w-0 flex-1 items-center gap-1 overflow-x-auto py-1';

  if (!editMode) {
    return (
      <div role="tablist" aria-label={t.dashboard.views.label} className={stripClasses}>
        {views.map((view) => (
          <TabButton key={view.id} view={view} label={labelOf(view)} active={view.id === activeView?.id} onSelect={setActiveView} />
        ))}
      </div>
    );
  }

  const handleDragEnd = ({ active, over }: DragEndEvent) => {
    if (!disabled && over && active.id !== over.id) moveView(String(active.id), String(over.id));
  };

  const confirmRemove = (view: DashboardView) => {
    if (window.confirm(t.dashboard.views.removeConfirm.replace('{name}', () => labelOf(view)))) removeView(view.id);
  };

  return (
    <div className="flex flex-wrap items-center gap-2">
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
        <SortableContext items={views.map((v) => v.id)} strategy={horizontalListSortingStrategy}>
          <div role="tablist" aria-label={t.dashboard.views.label} className={stripClasses}>
            {views.map((view) => (
              <SortableTab
                key={view.id}
                view={view}
                label={labelOf(view)}
                active={view.id === activeView?.id}
                disabled={disabled}
                renaming={!disabled && renamingId === view.id}
                onSelect={setActiveView}
                onRename={(name) => {
                  renameView(view.id, name);
                  setRenamingId(null);
                }}
                onCancelRename={() => setRenamingId(null)}
              />
            ))}
          </div>
        </SortableContext>
      </DndContext>
      {activeView && (
        <div className="flex shrink-0 items-center gap-1">
          <Button
            variant="ghost"
            size="sm"
            icon={<Pencil className="h-3.5 w-3.5" />}
            disabled={disabled}
            onClick={() => setRenamingId(activeView.id)}
            aria-label={t.dashboard.views.rename}
            title={t.dashboard.views.rename}
          />
          <Button
            variant="ghost"
            size="sm"
            icon={<Copy className="h-3.5 w-3.5" />}
            disabled={disabled || atLimit}
            onClick={() => duplicateView(activeView.id, t.dashboard.views.copyName.replace('{name}', () => labelOf(activeView)))}
            aria-label={t.dashboard.views.duplicate}
            title={atLimit ? limitHint : t.dashboard.views.duplicate}
          />
          <Button
            variant="ghost"
            size="sm"
            icon={<Trash2 className="h-3.5 w-3.5" />}
            disabled={disabled || views.length < 2}
            onClick={() => confirmRemove(activeView)}
            aria-label={t.dashboard.views.remove}
            title={views.length < 2 ? t.dashboard.views.lastView : t.dashboard.views.remove}
          />
          <Button
            variant="secondary"
            size="sm"
            icon={<Plus className="h-3.5 w-3.5" />}
            disabled={disabled || atLimit}
            onClick={() => {
              const id = addView();
              if (id) setRenamingId(id);
            }}
            aria-label={t.dashboard.views.add}
            title={atLimit ? limitHint : t.dashboard.views.add}
          />
        </div>
      )}
    </div>
  );
}
