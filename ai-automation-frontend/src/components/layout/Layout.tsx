import { Suspense } from 'react';
import { Outlet } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { Header } from './Header';
import { SecurityBanner } from './SecurityBanner';
import { ApiKeyGuard } from './ApiKeyGuard';
import { Spinner } from '../ui';
import { useSidebarStore } from '../../stores/sidebarStore';

export function Layout() {
  const collapsed = useSidebarStore((s) => s.collapsed);

  return (
    <ApiKeyGuard>
      <div className="min-h-screen bg-surface">
        <Sidebar />
        <div
          className="transition-all duration-200"
          style={{ marginLeft: collapsed ? 64 : 224 }}
        >
          <SecurityBanner />
          <Header />
          <main className="p-6">
            <Suspense fallback={<div className="flex items-center justify-center h-64"><Spinner /></div>}>
              <Outlet />
            </Suspense>
          </main>
        </div>
      </div>
    </ApiKeyGuard>
  );
}
