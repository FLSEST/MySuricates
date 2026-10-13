import { useState, useCallback, useEffect } from 'react';
import { useSettingsStore } from '../stores/settingsStore';
import { useT } from '../i18n';
import { useSystemInfo, useRepairAllFailed } from '../hooks/useSystem';
import { useStagingStatus, useStagingStart, useStagingStop, useStagingPromote, useStagingCancel, useStagingSync } from '../hooks/useStaging';
import { useTestingStatus, useTestingStart, useTestingStop, useTestingReset, useTestingSync, useTestingPullChanges } from '../hooks/useTesting';
import { GeneralSettingsCard } from '../components/settings/GeneralSettingsCard';
import { AiProviderStatusCard } from '../components/settings/AiProviderStatusCard';
import { AppearanceCard } from '../components/settings/AppearanceCard';
import { TraySettingsCard } from '../components/settings/TraySettingsCard';
import { SecurityCard } from '../components/settings/SecurityCard';
import { TodoSettingsCard } from '../components/settings/TodoSettingsCard';
import { RemoteAccessCard } from '../components/settings/RemoteAccessCard';
import { BackupCard } from '../components/settings/BackupCard';
import { SystemInfoCard } from '../components/settings/SystemInfoCard';
import { ListenAddressesCard } from '../components/settings/ListenAddressesCard';
import { StagingCard } from '../components/settings/StagingCard';
import { TestingCard } from '../components/settings/TestingCard';
import { StagedTargetsCard } from '../components/settings/StagedTargetsCard';
import { useStagedTargets } from '../hooks/useStagedSync';
import { NotificationsCard } from '../components/settings/NotificationsCard';
import { ExecutionSettingsCard } from '../components/settings/ExecutionSettingsCard';
import { WishSettingsCard } from '../components/settings/WishSettingsCard';
import { SettingsNav, type NavSection } from '../components/settings/SettingsNav';
import { SettingsSection } from '../components/settings/SettingsSection';
import { ModulesCard } from '../components/settings/ModulesCard';
import { StartupConfigCard } from '../components/settings/StartupConfigCard';
import { McpConnectCard } from '../components/settings/McpConnectCard';
import { useModuleEnabled, useModuleInfo } from '../hooks/useModules';
import { useRequestMetrics } from '../hooks/useRequestMetrics';
import { ApiLatencyCard } from '../components/dashboard/ApiLatencyCard';
import { ProHandoff } from '../components/simple/ProHandoff';

export function Settings() {
  const t = useT();
  const { simpleMode, advancedMode } = useSettingsStore();
  const { data: sysInfo } = useSystemInfo();
  const { data: requestMetrics } = useRequestMetrics();
  const stagingModule = useModuleEnabled('staging');
  const remoteAccessModule = useModuleEnabled('remote-access');
  const wishesModule = useModuleEnabled('wishes');
  // Strict, unlike useModuleEnabled: an older backend that does not report `mcp` has no MCP endpoint,
  // so the connect instructions would point nowhere
  const mcpModule = useModuleInfo('mcp')?.enabled === true;

  const { data: staging } = useStagingStatus();
  const stagingStart = useStagingStart();
  const stagingStop = useStagingStop();
  const stagingPromote = useStagingPromote();
  const stagingCancel = useStagingCancel();
  const stagingSync = useStagingSync();

  const { data: testing } = useTestingStatus();
  const testingStart = useTestingStart();
  const testingStop = useTestingStop();
  const testingReset = useTestingReset();
  const testingSync = useTestingSync();
  const testingPullChanges = useTestingPullChanges();
  const repairAllFailed = useRepairAllFailed();
  // Staged setup (84a78bb7): the sync targets come from the startup configuration — without advanced mode too
  const { data: stagedTargets } = useStagedTargets();
  const hasStagedTargets = (stagedTargets?.length ?? 0) > 0;
  const showEnvironments = stagingModule && (advancedMode || hasStagedTargets);

  // Collapsible section state — all start closed except general
  // A deep link (#section) opens its section from the start; the effect below only scrolls to it
  const [openSections, setOpenSections] = useState<Record<string, boolean>>(() => {
    const targetId = window.location.hash.slice(1);
    return targetId ? { [targetId]: true } : {};
  });

  const toggleSection = useCallback((id: string) => {
    setOpenSections(prev => ({ ...prev, [id]: !prev[id] }));
  }, []);

  const handleNavigate = useCallback((id: string) => {
    // Open the target section when navigating via sidebar
    setOpenSections(prev => ({ ...prev, [id]: true }));
  }, []);

  useEffect(() => {
    const targetId = window.location.hash.slice(1);
    if (!targetId) return;
    const timer = window.setTimeout(() => {
      document.getElementById(targetId)?.scrollIntoView({ block: 'start' });
    }, 0);
    // Left before it ran: the next page must not be scrolled to an element of the same id
    return () => window.clearTimeout(timer);
  }, []);

  const sections: NavSection[] = [
    { id: 'general', label: t.settings.settingsNavGeneral, visible: true },
    { id: 'appearance', label: t.settings.appearance, visible: true },
    { id: 'tray', label: t.settings.trayNavTitle, visible: true, parentId: 'appearance' },
    { id: 'execution', label: t.settings.settingsNavExecution, visible: !simpleMode },
    { id: 'infrastructure', label: t.settings.settingsNavInfrastructure, visible: !simpleMode },
    { id: 'environments', label: t.settings.settingsNavEnvironments, visible: showEnvironments },
    { id: 'modules', label: t.modules.navTitle, visible: true },
    { id: 'mcp', label: t.mcpConnect.navTitle, visible: mcpModule },
    { id: 'system', label: t.settings.settingsNavSystem, visible: true },
  ];

  return (
    <div className="animate-fade-in max-w-4xl">
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-text">{t.settings.title}</h1>
        <p className="text-sm text-text-secondary mt-1">{t.settings.subtitle}</p>
      </div>

      {/* Below md the nav is a chip row above the content – side by side it squeezed the content to 0 px (1a89e149) */}
      <div className="flex flex-col gap-4 md:flex-row md:gap-8">
        <SettingsNav sections={sections} onNavigate={handleNavigate} />

        <div className="flex-1 min-w-0 space-y-10">
          <SettingsSection id="general" title={t.settings.settingsNavGeneral}>
            <GeneralSettingsCard />
            <AiProviderStatusCard />
            <TodoSettingsCard />
            <SecurityCard />
          </SettingsSection>

          <SettingsSection id="appearance" title={t.settings.appearance} collapsible open={!!openSections['appearance']} onToggle={() => toggleSection('appearance')}>
            <AppearanceCard />
          </SettingsSection>

          <SettingsSection id="tray" title={t.settings.trayNavTitle}>
            <TraySettingsCard />
          </SettingsSection>

          {/* Simple mode hides execution + infrastructure — name them and offer the switch */}
          {simpleMode && <ProHandoff reason={t.simple.handoff.settingsHidden} />}

          {!simpleMode && (
            <SettingsSection id="execution" title={t.settings.settingsNavExecution} collapsible open={!!openSections['execution']} onToggle={() => toggleSection('execution')}>
              <ExecutionSettingsCard />
              {wishesModule && <WishSettingsCard />}
            </SettingsSection>
          )}

          {!simpleMode && (
            <SettingsSection id="infrastructure" title={t.settings.settingsNavInfrastructure} collapsible open={!!openSections['infrastructure']} onToggle={() => toggleSection('infrastructure')}>
              {remoteAccessModule && <RemoteAccessCard />}
              <BackupCard />
              <NotificationsCard />
            </SettingsSection>
          )}

          {showEnvironments && (
            <SettingsSection id="environments" title={t.settings.settingsNavEnvironments} collapsible open={!!openSections['environments']} onToggle={() => toggleSection('environments')}>
              {hasStagedTargets && <StagedTargetsCard targets={stagedTargets ?? []} />}
              {advancedMode && (
                <>
                  <StagingCard
                    staging={staging}
                    devVersion={sysInfo?.version}
                    onStart={() => stagingStart.mutate()}
                    onStop={() => stagingStop.mutate()}
                    onPromote={() => stagingPromote.mutate()}
                    onCancel={() => stagingCancel.mutate()}
                    onSyncAll={() => stagingSync.mutateAsync(undefined)}
                    onSyncSelective={(req) => stagingSync.mutateAsync(req)}
                    syncResult={stagingSync.data}
                    startLoading={stagingStart.isPending}
                    stopLoading={stagingStop.isPending}
                    promoteLoading={stagingPromote.isPending}
                    syncLoading={stagingSync.isPending}
                  />
                  <TestingCard
                    testing={testing}
                    devVersion={sysInfo?.version}
                    onStart={() => testingStart.mutate()}
                    onStop={() => testingStop.mutate()}
                    onReset={() => testingReset.mutateAsync()}
                    onSyncAll={() => testingSync.mutateAsync(undefined)}
                    onSyncSelective={(req) => testingSync.mutateAsync(req)}
                    onPullChanges={() => testingPullChanges.mutateAsync()}
                    onRepairAll={() => repairAllFailed.mutate(false)}
                    syncResult={testingSync.data}
                    pullResult={testingPullChanges.data}
                    startLoading={testingStart.isPending}
                    stopLoading={testingStop.isPending}
                    resetLoading={testingReset.isPending}
                    syncLoading={testingSync.isPending}
                    pullLoading={testingPullChanges.isPending}
                    repairAllLoading={repairAllFailed.isPending}
                  />
                </>
              )}
            </SettingsSection>
          )}

          <SettingsSection id="modules" title={t.modules.navTitle}>
            <StartupConfigCard />
            <ModulesCard />
          </SettingsSection>

          {mcpModule && (
            <SettingsSection id="mcp" title={t.mcpConnect.navTitle} collapsible open={!!openSections['mcp']} onToggle={() => toggleSection('mcp')}>
              <McpConnectCard />
            </SettingsSection>
          )}

          <SettingsSection id="system" title={t.settings.settingsNavSystem} collapsible open={!!openSections['system']} onToggle={() => toggleSection('system')}>
            <SystemInfoCard />
            <ListenAddressesCard />
            <ApiLatencyCard data={requestMetrics} />
          </SettingsSection>
        </div>
      </div>
    </div>
  );
}
