import { useState } from 'react';
import { NavLink } from 'react-router-dom';
import { Settings, ChevronLeft, ChevronRight, Pencil, Check, RotateCcw, Plus } from 'lucide-react';
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
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { useSidebarStore } from '../../stores/sidebarStore';
import { useSettingsStore } from '../../stores/settingsStore';
import { useIsMobile } from '../../hooks/useIsMobile';
import { useSystemInfo } from '../../hooks/useSystem';
import { sidebarEntryId, useSidebarOrder } from '../../hooks/useSidebarOrder';
import { useT } from '../../i18n';
import { isNestedAt } from '../../utils/navItems';
import { MeerkatIcon } from './MeerkatIcon';
import { HealthIndicator } from './HealthIndicator';
import { SortableNavItem } from './SortableNavItem';
import { SidebarDivider, SortableDivider } from './SidebarDivider';
import { PendingInputNavItem } from './PendingInputNavItem';
import { SidebarModeToggle } from './SidebarModeToggle';

// AssistantPanel still imports the icon from here; the other callers use './MeerkatIcon'.
export { MeerkatIcon };

export function Sidebar() {
  const { collapsed, toggle, mobileOpen, setMobileOpen } = useSidebarStore();
  const { simpleMode } = useSettingsStore();
  const isMobile = useIsMobile();
  const { data: systemInfo } = useSystemInfo();
  const t = useT();
  const {
    visibleEntries, editModeEntries, hiddenNavItems, dividerLabels, setDividerLabel,
    moveEntry, addDivider, removeDivider, hide, show, reset,
  } = useSidebarOrder();
  const [editModeState, setEditMode] = useState(false);
  // Simple mode never edits the menu — also when it was switched on mid-edit.
  const editMode = editModeState && !simpleMode;

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (!over || active.id === over.id) return;
    moveEntry(active.id as string, over.id as string);
  };

  const isOpen = isMobile ? mobileOpen : true;
  const showLabels = isMobile ? true : !collapsed;

  return (
    <aside
      className={`fixed left-0 top-0 h-screen flex flex-col z-40 transition-all duration-200
                  ${isMobile ? 'w-56' : collapsed ? 'w-16' : 'w-56'}
                  ${isMobile ? (isOpen ? 'translate-x-0' : '-translate-x-full') : ''}`}
      style={{ backgroundColor: 'var(--color-sidebar)' }}
    >
      {/* Logo */}
      <div
        className={`flex flex-col items-center ${collapsed && !isMobile ? 'px-2 py-3' : 'px-3 pt-4 pb-3'}`}
        style={{ borderBottom: '1px solid var(--color-sidebar-border)' }}
      >
        <MeerkatIcon className="text-(--color-accent)" size={collapsed && !isMobile ? 48 : 140} />
        {showLabels ? (
          <>
            <span className="mt-1.5 text-lg font-bold tracking-tight text-[var(--color-sidebar-text)]">
              MySuricates
            </span>
            {systemInfo?.environment && (
              <span className={`text-[10px] font-semibold uppercase tracking-widest mt-0.5 px-2 py-0.5 rounded-full ${
                systemInfo.environment === 'Produktiv'
                  ? 'bg-success-subtle text-success'
                  : 'bg-warning-subtle text-warning'
              }`}>
                {systemInfo.environment}
              </span>
            )}
            <SidebarModeToggle collapsed={false} />
          </>
        ) : (
          <>
            {systemInfo?.environment && (
              <span className={`w-2 h-2 rounded-full mt-1 ${
                systemInfo.environment === 'Produktiv' ? 'bg-emerald-400' : 'bg-amber-400'
              }`} title={systemInfo.environment} />
            )}
            <SidebarModeToggle collapsed />
          </>
        )}
      </div>

      {/* Navigation */}
      <nav className="flex-1 py-3 px-2 space-y-0.5 overflow-y-auto">
        {!editMode && (
          <PendingInputNavItem showLabels={showLabels} onNavigate={() => { if (isMobile) setMobileOpen(false); }} />
        )}
        {editMode && !collapsed ? (
          <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
            <SortableContext items={editModeEntries.map(sidebarEntryId)} strategy={verticalListSortingStrategy}>
              {editModeEntries.map((entry, index) =>
                entry.kind === 'divider' ? (
                  <SortableDivider
                    key={entry.id}
                    id={entry.id}
                    label={dividerLabels[entry.id] ?? ''}
                    onLabelChange={setDividerLabel}
                    onRemove={removeDivider}
                  />
                ) : (
                  <SortableNavItem
                    key={entry.item.to}
                    id={entry.item.to}
                    item={entry.item}
                    nested={isNestedAt(editModeEntries, index)}
                    onHide={hide}
                  />
                ),
              )}
            </SortableContext>
            <button
              onClick={addDivider}
              className="mt-1 flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-sm text-[var(--color-sidebar-text-muted)] transition-colors hover:bg-[var(--color-sidebar-hover)] hover:text-[var(--color-sidebar-text)]"
            >
              <Plus className="w-3.5 h-3.5 shrink-0" />
              <span className="truncate">{t.nav.addDivider}</span>
            </button>
          </DndContext>
        ) : (
          visibleEntries.map((entry, index) => {
            if (entry.kind === 'divider') {
              return <SidebarDivider key={entry.id} label={dividerLabels[entry.id]} showLabels={showLabels} />;
            }
            const item = entry.item;
            // Icons only (collapsed): a sub-entry looks like any other entry.
            const nested = showLabels && isNestedAt(visibleEntries, index);
            const iconSize = nested ? 16 : 18;
            return (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.to === '/'}
                className={({ isActive }) =>
                  `flex items-center gap-3 ${nested ? 'pl-8 pr-3 py-1.5' : 'px-3 py-2'} rounded-lg text-sm font-medium transition-colors
                   ${isActive
                     ? 'text-[var(--color-sidebar-text)]'
                     : 'text-[var(--color-sidebar-text-muted)] hover:bg-[var(--color-sidebar-hover)] hover:text-[var(--color-sidebar-text)]'
                   }`
                }
                style={({ isActive }) => ({
                  backgroundColor: isActive ? 'var(--color-sidebar-active)' : undefined,
                })}
                onClick={() => { if (isMobile) setMobileOpen(false); }}
              >
                <item.icon className="w-4.5 h-4.5 shrink-0" style={{ width: iconSize, height: iconSize }} />
                {showLabels && <span className="truncate">{item.label}</span>}
              </NavLink>
            );
          })
        )}

        {/* Hidden items — show as "+ add back" in edit mode */}
        {editMode && !collapsed && hiddenNavItems.length > 0 && (
          <div className="space-y-0.5 pt-2 mt-2" style={{ borderTop: '1px solid var(--color-sidebar-border)' }}>
            {hiddenNavItems.map((item) => (
              <button
                key={item.to}
                onClick={() => show(item.to)}
                className="flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-sm text-[var(--color-sidebar-text-muted)] transition-colors hover:bg-[var(--color-sidebar-hover)] hover:text-[var(--color-sidebar-text)]"
              >
                <Plus className="w-3.5 h-3.5 shrink-0" />
                <item.icon style={{ width: 14, height: 14 }} className="shrink-0" />
                <span className="truncate">{item.label}</span>
              </button>
            ))}
          </div>
        )}
      </nav>

      {/* Edit menu — Pro only: Simple shows a fixed, calm set of pages */}
      {showLabels && !simpleMode && (
        <div className="flex items-center gap-1 px-2 py-1">
          <button
            onClick={() => setEditMode((v) => !v)}
            className={`flex items-center gap-2 flex-1 px-3 py-1.5 rounded-lg text-sm transition-colors ${
              editMode
                ? 'text-accent bg-accent/10'
                : 'text-[var(--color-sidebar-text-muted)] hover:bg-[var(--color-sidebar-hover)] hover:text-[var(--color-sidebar-text)]'
            }`}
            aria-label={editMode ? t.nav.finishEditing : t.nav.editMenu}
          >
            {editMode ? (
              <>
                <Check className="w-[16px] h-[16px] shrink-0" />
                <span>{t.nav.editDone}</span>
              </>
            ) : (
              <>
                <Pencil className="w-[16px] h-[16px] shrink-0" />
                <span>{t.nav.editMenu}</span>
              </>
            )}
          </button>
          {editMode && (
            <button
              onClick={reset}
              className="rounded-lg p-2 text-[var(--color-sidebar-text-muted)] transition-colors hover:bg-[var(--color-sidebar-hover)] hover:text-[var(--color-sidebar-text)]"
              aria-label={t.nav.resetMenu}
              title={t.nav.resetMenuHint}
            >
              <RotateCcw className="w-[14px] h-[14px]" />
            </button>
          )}
        </div>
      )}

      {/* Settings + Collapse */}
      <div className="space-y-0.5 p-2" style={{ borderTop: '1px solid var(--color-sidebar-border)' }}>
        <NavLink
          to="/settings"
          className={({ isActive }) =>
            `flex items-center gap-3 px-3 py-2 rounded-lg text-sm font-medium transition-colors
             ${isActive
               ? 'text-[var(--color-sidebar-text)]'
               : 'text-[var(--color-sidebar-text-muted)] hover:bg-[var(--color-sidebar-hover)] hover:text-[var(--color-sidebar-text)]'}`
          }
          style={({ isActive }) => ({
            backgroundColor: isActive ? 'var(--color-sidebar-active)' : undefined,
          })}
          onClick={() => { if (isMobile) setMobileOpen(false); }}
        >
          <Settings className="w-[18px] h-[18px] shrink-0" />
          {showLabels && <span>{t.nav.settings}</span>}
        </NavLink>

        <HealthIndicator collapsed={collapsed && !isMobile} />

        {!isMobile && (
          <button
            onClick={toggle}
            className="flex w-full items-center gap-3 rounded-lg px-3 py-2 text-sm text-[var(--color-sidebar-text-muted)]
                       transition-colors hover:bg-[var(--color-sidebar-hover)] hover:text-[var(--color-sidebar-text)]"
            aria-label={collapsed ? t.common.expand : t.common.collapse}
          >
            {collapsed ? (
              <ChevronRight className="w-[18px] h-[18px]" />
            ) : (
              <>
                <ChevronLeft className="w-[18px] h-[18px]" />
                <span>{t.common.collapse}</span>
              </>
            )}
          </button>
        )}
      </div>
    </aside>
  );
}
