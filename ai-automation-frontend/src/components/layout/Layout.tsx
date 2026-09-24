import { Suspense, useEffect, useState } from 'react';
import { Outlet } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { Header } from './Header';
import { ProPageNotice } from './ProPageNotice';
import { SecurityBanner } from './SecurityBanner';
import { RateLimitBanner } from './RateLimitBanner';
import { ApiKeyGuard } from './ApiKeyGuard';
import { AssistantPanel } from '../AssistantPanel';
import { CommandPalette } from '../palette/CommandPalette';
import { WalkthroughGate } from '../walkthrough/Walkthrough';
import { Spinner } from '../ui';
import { useSidebarStore } from '../../stores/sidebarStore';
import { useIsMobile } from '../../hooks/useIsMobile';
import { useModuleEnabled } from '../../hooks/useModules';

export function Layout() {
  const collapsed = useSidebarStore((s) => s.collapsed);
  const mobileOpen = useSidebarStore((s) => s.mobileOpen);
  const setMobileOpen = useSidebarStore((s) => s.setMobileOpen);
  const isMobile = useIsMobile();
  const [paletteOpen, setPaletteOpen] = useState(false);
  const assistantModule = useModuleEnabled('assistant');

  useEffect(() => {
    const keyHandler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen((v) => !v);
      }
    };
    const openHandler = () => setPaletteOpen(true);
    window.addEventListener('keydown', keyHandler);
    window.addEventListener('mysuri:openPalette', openHandler as EventListener);
    return () => {
      window.removeEventListener('keydown', keyHandler);
      window.removeEventListener('mysuri:openPalette', openHandler as EventListener);
    };
  }, []);

  return (
    <ApiKeyGuard>
      <div className="min-h-screen overflow-x-hidden bg-surface">
        <Sidebar />

        {/* Mobile backdrop */}
        {isMobile && mobileOpen && (
          <div
            className="fixed inset-0 z-30 bg-black/50 backdrop-blur-xs animate-fade-in"
            onClick={() => setMobileOpen(false)}
          />
        )}

        <div
          className="transition-all duration-200"
          style={{ marginLeft: isMobile ? 0 : collapsed ? 64 : 224 }}
        >
          <SecurityBanner />
          <RateLimitBanner />
          <Header />
          <ProPageNotice />
          <main className={isMobile ? 'p-4 pb-20' : 'p-6 pb-20'}>
            <Suspense fallback={<div className="flex items-center justify-center h-64"><Spinner /></div>}>
              <Outlet />
            </Suspense>
          </main>
        </div>
      </div>
      {assistantModule && <AssistantPanel />}
      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} />
      <WalkthroughGate />
    </ApiKeyGuard>
  );
}
