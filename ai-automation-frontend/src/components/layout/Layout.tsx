import { Outlet } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { Header } from './Header';
import { useSidebarStore } from '../../stores/sidebarStore';

export function Layout() {
  const collapsed = useSidebarStore((s) => s.collapsed);

  return (
    <div className="min-h-screen bg-surface">
      <Sidebar />
      <div
        className="transition-all duration-200"
        style={{ marginLeft: collapsed ? 64 : 224 }}
      >
        <Header />
        <main className="p-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
