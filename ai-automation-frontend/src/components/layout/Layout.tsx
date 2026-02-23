import { Suspense } from 'react';
import { Outlet } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { Header } from './Header';
import { SecurityBanner } from './SecurityBanner';
import { ApiKeyGuard } from './ApiKeyGuard';
import { Spinner } from '../ui';
import { useSidebarStore } from '../../stores/sidebarStore';
import { useIsMobile } from '../../hooks/useIsMobile';

export function Layout() {
  const collapsed = useSidebarStore((s) => s.collapsed);
  const mobileOpen = useSidebarStore((s) => s.mobileOpen);
  const setMobileOpen = useSidebarStore((s) => s.setMobileOpen);
  const isMobile = useIsMobile();

  return (
    <ApiKeyGuard>
      <div className="min-h-screen bg-surface">
        <Sidebar />

        {/* Mobile backdrop */}
        {isMobile && mobileOpen && (
          <div
            className="fixed inset-0 z-30 bg-black/50 backdrop-blur-sm animate-fade-in"
            onClick={() => setMobileOpen(false)}
          />
        )}

        <div
          className="transition-all duration-200"
          style={{ marginLeft: isMobile ? 0 : collapsed ? 64 : 224 }}
        >
          <SecurityBanner />
          <Header />
          <main className={isMobile ? 'p-4' : 'p-6'}>
            <Suspense fallback={<div className="flex items-center justify-center h-64"><Spinner /></div>}>
              <Outlet />
            </Suspense>
          </main>
        </div>
      </div>
    </ApiKeyGuard>
  );
}
