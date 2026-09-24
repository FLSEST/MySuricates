import { useState } from 'react';
import { NavLink } from 'react-router-dom';
import {
  LayoutDashboard, FolderKanban, GitBranch, History, Zap, Sparkles, Factory,
  Calendar, CalendarDays, Webhook, Plug, ScrollText, CheckSquare, Settings, ChevronLeft, ChevronRight, Link2, BookOpen, BookMarked, BarChart2,
  Pencil, Check, RotateCcw, Plus, MessageCircle, ClipboardList, LayoutGrid, Bug,
} from 'lucide-react';
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
  arrayMove,
} from '@dnd-kit/sortable';
import { useSidebarStore } from '../../stores/sidebarStore';
import { useSettingsStore } from '../../stores/settingsStore';
import { useIsMobile } from '../../hooks/useIsMobile';
import { useSystemInfo } from '../../hooks/useSystem';
import { useSystemModules } from '../../hooks/useModules';
import { useT } from '../../i18n';
import {
  collapseDividers, effectiveOrder, newDividerId, resolveEntries, withDefaultDividers,
} from '../../utils/sidebarLayout';
import { SIMPLE_MODE_ROUTES } from '../../utils/simpleRoutes';
import { NAV_TILES } from '../../utils/homeTiles';
import type { TranslationKeys } from '../../i18n/en';
import type { FeatureModuleName } from '../../types';
import { HealthIndicator } from './HealthIndicator';
import { SortableNavItem } from './SortableNavItem';
import { SidebarDivider, SortableDivider } from './SidebarDivider';
import { PendingInputNavItem } from './PendingInputNavItem';

export function MeerkatIcon({ className, size = 24 }: { className?: string; size?: number }) {
  const bg = 'var(--color-sidebar, #1a1614)';
  return (
    <svg viewBox="0 0 48 32" fill="none" className={className} style={{ width: size, height: size * 32 / 48 }} xmlns="http://www.w3.org/2000/svg">
      {/* Hill */}
      <path d="M0 28 Q10 22 20 24 Q28 21 36 23 Q42 22 48 27 L48 32 L0 32 Z" fill="currentColor" opacity="0.15"/>

      {/* Far-left meerkat (tiny) */}
      <g opacity="0.4">
        <ellipse cx="6" cy="18.5" rx="1.8" ry="2.2" fill="currentColor"/>
        <path d="M4.5 20.5 Q4.4 23 4.6 26 Q5 27 6 27 Q7 27 7.4 26 Q7.6 23 7.5 20.5 Z" fill="currentColor"/>
      </g>

      {/* Left meerkat (medium) */}
      <g opacity="0.65">
        <ellipse cx="14" cy="13" rx="2.5" ry="3" fill="currentColor"/>
        <circle cx="11.8" cy="10.8" r="0.9" fill="currentColor" opacity="0.7"/>
        <circle cx="16.2" cy="10.8" r="0.9" fill="currentColor" opacity="0.7"/>
        <circle cx="13" cy="12.5" r="0.5" fill={bg}/>
        <circle cx="15" cy="12.5" r="0.5" fill={bg}/>
        <path d="M11.8 15.8 Q11.5 19 11.7 24 Q12 25.5 14 25.5 Q16 25.5 16.3 24 Q16.5 19 16.2 15.8 Z" fill="currentColor"/>
      </g>

      {/* CENTER meerkat (tallest, with glasses!) */}
      <g>
        <ellipse cx="24" cy="8" rx="3.2" ry="3.8" fill="currentColor"/>
        {/* Ears */}
        <circle cx="21.2" cy="5" r="1.1" fill="currentColor" opacity="0.7"/>
        <circle cx="26.8" cy="5" r="1.1" fill="currentColor" opacity="0.7"/>
        {/* Glasses frame */}
        <circle cx="22.5" cy="7.8" r="1.5" fill="none" stroke={bg} strokeWidth="0.5"/>
        <circle cx="25.5" cy="7.8" r="1.5" fill="none" stroke={bg} strokeWidth="0.5"/>
        <line x1="24" y1="7.8" x2="24" y2="7.8" stroke={bg} strokeWidth="0.5"/>
        <path d="M24 7.8 L24 7.8" stroke={bg} strokeWidth="0.4"/>
        {/* Bridge */}
        <path d="M23.9 7.6 Q24 7.2 24.1 7.6" stroke={bg} strokeWidth="0.4" fill="none"/>
        {/* Temple arms */}
        <path d="M21 7.8 Q20.2 7.5 20.5 6.8" stroke={bg} strokeWidth="0.35" fill="none"/>
        <path d="M27 7.8 Q27.8 7.5 27.5 6.8" stroke={bg} strokeWidth="0.35" fill="none"/>
        {/* Eyes behind glasses */}
        <circle cx="22.5" cy="7.8" r="0.6" fill={bg}/>
        <circle cx="25.5" cy="7.8" r="0.6" fill={bg}/>
        {/* Eye shine */}
        <circle cx="22.3" cy="7.5" r="0.2" fill="white" opacity="0.7"/>
        <circle cx="25.3" cy="7.5" r="0.2" fill="white" opacity="0.7"/>
        {/* Body */}
        <path d="M21.2 11.5 Q20.8 16 21 21 Q21.5 24 24 24 Q26.5 24 27 21 Q27.2 16 26.8 11.5 Z" fill="currentColor"/>
        {/* Arms */}
        <path d="M21.2 14.5 Q19 16 19 17.2" stroke="currentColor" strokeWidth="1" strokeLinecap="round" fill="none" opacity="0.6"/>
        <path d="M26.8 14.5 Q29 16 29 17.2" stroke="currentColor" strokeWidth="1" strokeLinecap="round" fill="none" opacity="0.6"/>
      </g>

      {/* Right meerkat (medium) */}
      <g opacity="0.6">
        <ellipse cx="34" cy="14" rx="2.3" ry="2.8" fill="currentColor"/>
        <circle cx="32" cy="12" r="0.8" fill="currentColor" opacity="0.7"/>
        <circle cx="36" cy="12" r="0.8" fill="currentColor" opacity="0.7"/>
        <circle cx="33" cy="13.5" r="0.5" fill={bg}/>
        <circle cx="35" cy="13.5" r="0.5" fill={bg}/>
        <path d="M32 16.5 Q31.8 19.5 32 24.5 Q32.3 26 34 26 Q35.7 26 36 24.5 Q36.2 19.5 36 16.5 Z" fill="currentColor"/>
      </g>

      {/* Far-right meerkat (tiny) */}
      <g opacity="0.35">
        <ellipse cx="42" cy="19.5" rx="1.7" ry="2" fill="currentColor"/>
        <path d="M40.5 21.3 Q40.4 23.5 40.6 26.5 Q41 27.3 42 27.3 Q43 27.3 43.4 26.5 Q43.6 23.5 43.5 21.3 Z" fill="currentColor"/>
      </g>
    </svg>
  );
}

interface NavItemDef {
  to: string;
  icon: typeof LayoutDashboard;
  labelKey: keyof TranslationKeys['nav'];
  /** Optional module the page belongs to; hidden when the backend reports it disabled. */
  module?: FeatureModuleName;
}

const navItems: NavItemDef[] = [
  { to: '/', icon: LayoutDashboard, labelKey: 'dashboard' },
  { to: '/projects', icon: FolderKanban, labelKey: 'projects' },
  { to: '/todos', icon: CheckSquare, labelKey: 'todos' },
  { to: '/workflows', icon: GitBranch, labelKey: 'workflows' },
  { to: '/runs', icon: History, labelKey: 'runs' },
  { to: '/debug', icon: Bug, labelKey: 'debug', module: 'debug' },
  { to: '/chains', icon: Link2, labelKey: 'chains', module: 'chains' },
  { to: '/cockpit', icon: LayoutGrid, labelKey: 'cockpit' },
  { to: '/calendar', icon: CalendarDays, labelKey: 'calendar' },
  { to: '/schedules', icon: Calendar, labelKey: 'schedules' },
  { to: '/webhooks', icon: Webhook, labelKey: 'webhooks', module: 'webhooks' },
  { to: '/integrations', icon: Plug, labelKey: 'integrations', module: 'integrations' },
  { to: '/onboarding-profiles', icon: BookMarked, labelKey: 'onboardingProfiles', module: 'onboarding-profiles' },
  { to: '/wishes', icon: Sparkles, labelKey: 'wishes', module: 'wishes' },
  { to: '/factory', icon: Factory, labelKey: 'factory', module: 'factory' },
  { to: '/dialogs', icon: MessageCircle, labelKey: 'dialogs', module: 'dialog-import' },
  { to: '/skills', icon: Zap, labelKey: 'skills', module: 'skills' },
  { to: '/insights', icon: BarChart2, labelKey: 'insights', module: 'learning' },
  { to: '/activity', icon: ScrollText, labelKey: 'activityLog' },
  { to: '/docs', icon: BookOpen, labelKey: 'docs' },
  { to: '/reports', icon: ClipboardList, labelKey: 'reports', module: 'audit' },
];

/** Default order incl. the default dividers — over all pages, so anchors of disabled modules still count. */
const DEFAULT_ORDER = withDefaultDividers(navItems.map((i) => i.to));

export function Sidebar() {
  const { collapsed, toggle, mobileOpen, setMobileOpen } = useSidebarStore();
  const {
    simpleMode, chooseMode, sidebarItemOrder, sidebarHiddenItems, sidebarDividerLabels,
    setSidebarItemOrder, setSidebarHiddenItems, setSidebarDividerLabel, setSidebarDividerLabels,
  } = useSettingsStore();
  const isMobile = useIsMobile();
  const { data: systemInfo } = useSystemInfo();
  const { data: systemModules } = useSystemModules();
  const t = useT();
  const [editModeState, setEditMode] = useState(false);
  // Simple mode never edits the menu — also when it was switched on mid-edit.
  const editMode = editModeState && !simpleMode;

  // Pages of disabled modules vanish entirely — also from edit mode and the "add back" list.
  const disabledModules = new Set(
    (systemModules?.modules ?? []).filter((m) => !m.enabled).map((m) => m.name),
  );
  const availableNavItems = navItems.filter((item) => !item.module || !disabledModules.has(item.module));

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  // Build label-enriched nav items once (needed for edit mode display).
  // Simple mode names each page like its Simple title (the home tiles), the home itself "Start".
  const simpleLabel = (to: string): string | undefined => {
    if (to === '/') return t.nav.simpleHome;
    const tile = NAV_TILES.find((tl) => tl.to === to);
    return tile ? t.simple.home.tiles[tile.id].title : undefined;
  };
  const navItemsWithLabels = availableNavItems.map((item) => ({
    ...item,
    label: (simpleMode ? simpleLabel(item.to) : undefined) ?? t.nav[item.labelKey],
  }));

  // Full order (custom or default, dividers included); new pages are appended.
  const fullOrder = effectiveOrder(sidebarItemOrder, DEFAULT_ORDER, navItemsWithLabels.map((i) => i.to));
  const shownItems = navItemsWithLabels.filter((item) => !sidebarHiddenItems.includes(item.to));

  // Dividers that separate nothing on this installation (disabled modules, hidden pages) drop out.
  const visibleEntries = simpleMode
    ? resolveEntries(fullOrder, navItemsWithLabels.filter((item) => SIMPLE_MODE_ROUTES.has(item.to)))
        .filter((entry) => entry.kind === 'item')
    : collapseDividers(resolveEntries(fullOrder, shownItems), (id) => Boolean(sidebarDividerLabels[id]));

  const hiddenNavItems = navItemsWithLabels.filter((item) => sidebarHiddenItems.includes(item.to));

  // Edit mode shows every divider as stored, so each one can be moved or removed.
  const editModeEntries = resolveEntries(fullOrder, shownItems);
  const entryId = (entry: (typeof editModeEntries)[number]) => (entry.kind === 'divider' ? entry.id : entry.item.to);

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (!over || active.id === over.id) return;
    // Move within the full order so entries not shown here (disabled modules) keep their place.
    const oldIndex = fullOrder.indexOf(active.id as string);
    const newIndex = fullOrder.indexOf(over.id as string);
    setSidebarItemOrder(arrayMove(fullOrder, oldIndex, newIndex));
  };

  const handleAddDivider = () => {
    setSidebarItemOrder([...fullOrder, newDividerId()]);
  };

  const handleRemoveDivider = (id: string) => {
    setSidebarItemOrder(fullOrder.filter((entry) => entry !== id));
    setSidebarDividerLabel(id, '');
  };

  const handleHide = (to: string) => {
    setSidebarHiddenItems([...sidebarHiddenItems, to]);
  };

  const handleShow = (to: string) => {
    setSidebarHiddenItems(sidebarHiddenItems.filter((h) => h !== to));
  };

  const handleReset = () => {
    setSidebarItemOrder([]);
    setSidebarHiddenItems([]);
    setSidebarDividerLabels({});
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
            <div
              className="relative mt-2 flex rounded-full p-0.5 text-xs"
              style={{ backgroundColor: 'color-mix(in srgb, var(--color-sidebar-text) 10%, transparent)' }}
            >
              <div
                className={`absolute inset-y-0.5 w-[calc(50%-2px)] rounded-full bg-accent/25 transition-transform duration-200 ease-in-out ${
                  simpleMode ? 'translate-x-0.5' : 'translate-x-[calc(100%+2px)]'
                }`}
              />
              <button
                onClick={() => chooseMode(true)}
                className={`relative z-10 flex items-center gap-1 px-3 py-1 rounded-full font-medium transition-colors ${
                  simpleMode
                    ? 'text-accent'
                    : 'text-[var(--color-sidebar-text-muted)] hover:text-[var(--color-sidebar-text)]'
                }`}
              >
                <Zap className="w-3 h-3" />
                {t.settings.simpleModeShort}
              </button>
              <button
                onClick={() => chooseMode(false)}
                className={`relative z-10 flex items-center gap-1 px-3 py-1 rounded-full font-medium transition-colors ${
                  !simpleMode
                    ? 'text-accent'
                    : 'text-[var(--color-sidebar-text-muted)] hover:text-[var(--color-sidebar-text)]'
                }`}
              >
                <Sparkles className="w-3 h-3" />
                {t.settings.fullModeShort}
              </button>
            </div>
          </>
        ) : (
          <>
            {systemInfo?.environment && (
              <span className={`w-2 h-2 rounded-full mt-1 ${
                systemInfo.environment === 'Produktiv' ? 'bg-emerald-400' : 'bg-amber-400'
              }`} title={systemInfo.environment} />
            )}
            {/* Collapsed: the Einfach|Pro pill has no room — one icon button switches the mode */}
            <button
              onClick={() => chooseMode(!simpleMode)}
              aria-label={simpleMode ? t.simple.handoff.switchToPro : t.simple.handoff.switchToSimple}
              title={simpleMode ? t.simple.handoff.switchToPro : t.simple.handoff.switchToSimple}
              className="mt-2 flex h-7 w-7 items-center justify-center rounded-full text-accent transition-colors hover:bg-[var(--color-sidebar-hover)]"
              style={{ backgroundColor: 'color-mix(in srgb, var(--color-sidebar-text) 10%, transparent)' }}
            >
              {simpleMode ? <Zap className="w-3.5 h-3.5" /> : <Sparkles className="w-3.5 h-3.5" />}
            </button>
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
            <SortableContext items={editModeEntries.map(entryId)} strategy={verticalListSortingStrategy}>
              {editModeEntries.map((entry) =>
                entry.kind === 'divider' ? (
                  <SortableDivider
                    key={entry.id}
                    id={entry.id}
                    label={sidebarDividerLabels[entry.id] ?? ''}
                    onLabelChange={setSidebarDividerLabel}
                    onRemove={handleRemoveDivider}
                  />
                ) : (
                  <SortableNavItem
                    key={entry.item.to}
                    id={entry.item.to}
                    item={entry.item}
                    onHide={handleHide}
                  />
                ),
              )}
            </SortableContext>
            <button
              onClick={handleAddDivider}
              className="mt-1 flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-sm text-[var(--color-sidebar-text-muted)] transition-colors hover:bg-[var(--color-sidebar-hover)] hover:text-[var(--color-sidebar-text)]"
            >
              <Plus className="w-3.5 h-3.5 shrink-0" />
              <span className="truncate">{t.nav.addDivider}</span>
            </button>
          </DndContext>
        ) : (
          visibleEntries.map((entry) => {
            if (entry.kind === 'divider') {
              return <SidebarDivider key={entry.id} label={sidebarDividerLabels[entry.id]} showLabels={showLabels} />;
            }
            const item = entry.item;
            return (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.to === '/'}
                className={({ isActive }) =>
                  `flex items-center gap-3 px-3 py-2 rounded-lg text-sm font-medium transition-colors
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
                <item.icon className="w-4.5 h-4.5 shrink-0" style={{ width: 18, height: 18 }} />
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
                onClick={() => handleShow(item.to)}
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
              onClick={handleReset}
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
