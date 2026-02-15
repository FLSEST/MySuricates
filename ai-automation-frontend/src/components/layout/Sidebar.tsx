import { NavLink } from 'react-router-dom';
import {
  LayoutDashboard, FolderKanban, GitBranch, Zap,
  Calendar, Webhook, Settings, ChevronLeft, ChevronRight, Bot,
} from 'lucide-react';
import { useSidebarStore } from '../../stores/sidebarStore';
import { useT } from '../../i18n';

const navItems = [
  { to: '/', icon: LayoutDashboard, labelKey: 'dashboard' as const },
  { to: '/projects', icon: FolderKanban, labelKey: 'projects' as const },
  { to: '/workflows', icon: GitBranch, labelKey: 'workflows' as const },
  { to: '/skills', icon: Zap, labelKey: 'skills' as const },
  { to: '/schedules', icon: Calendar, labelKey: 'schedules' as const },
  { to: '/webhooks', icon: Webhook, labelKey: 'webhooks' as const },
];

export function Sidebar() {
  const { collapsed, toggle } = useSidebarStore();
  const t = useT();

  return (
    <aside
      className={`fixed left-0 top-0 h-screen flex flex-col z-40 transition-all duration-200
                  ${collapsed ? 'w-16' : 'w-56'}`}
      style={{ backgroundColor: 'var(--color-sidebar)' }}
    >
      {/* Logo */}
      <div className="flex items-center gap-3 px-4 h-14 border-b border-white/10">
        <div className="w-8 h-8 rounded-lg bg-accent/20 flex items-center justify-center shrink-0">
          <Bot className="w-5 h-5 text-accent" />
        </div>
        {!collapsed && (
          <span className="text-sm font-bold text-white tracking-tight truncate">
            AutoPilot
          </span>
        )}
      </div>

      {/* Navigation */}
      <nav className="flex-1 py-3 px-2 space-y-0.5 overflow-y-auto">
        {navItems.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.to === '/'}
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2 rounded-lg text-sm font-medium transition-colors
               ${isActive
                 ? 'text-white'
                 : 'text-slate-400 hover:text-white'
               }`
            }
            style={({ isActive }) => ({
              backgroundColor: isActive ? 'var(--color-sidebar-active)' : undefined,
            })}
          >
            <item.icon className="w-4.5 h-4.5 shrink-0" style={{ width: 18, height: 18 }} />
            {!collapsed && <span className="truncate">{t.nav[item.labelKey]}</span>}
          </NavLink>
        ))}
      </nav>

      {/* Settings + Collapse */}
      <div className="p-2 border-t border-white/10 space-y-0.5">
        <NavLink
          to="/settings"
          className={({ isActive }) =>
            `flex items-center gap-3 px-3 py-2 rounded-lg text-sm font-medium transition-colors
             ${isActive ? 'text-white' : 'text-slate-400 hover:text-white'}`
          }
          style={({ isActive }) => ({
            backgroundColor: isActive ? 'var(--color-sidebar-active)' : undefined,
          })}
        >
          <Settings className="w-[18px] h-[18px] shrink-0" />
          {!collapsed && <span>{t.nav.settings}</span>}
        </NavLink>

        <button
          onClick={toggle}
          className="flex items-center gap-3 px-3 py-2 rounded-lg text-sm text-slate-500
                     hover:text-slate-300 transition-colors w-full"
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
      </div>
    </aside>
  );
}
