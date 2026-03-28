import { useState, useCallback } from 'react';
import { useSettingsStore } from '../stores/settingsStore';
import { useT } from '../i18n';
import { useSystemInfo, useRepairAllFailed } from '../hooks/useSystem';
import { useStagingStatus, useStagingStart, useStagingStop, useStagingPromote, useStagingCancel, useStagingSync } from '../hooks/useStaging';
import { useTestingStatus, useTestingStart, useTestingStop, useTestingReset, useTestingSync, useTestingPullChanges } from '../hooks/useTesting';
import { GeneralSettingsCard } from '../components/settings/GeneralSettingsCard';
import { AppearanceCard } from '../components/settings/AppearanceCard';
import { SecurityCard } from '../components/settings/SecurityCard';
import { RemoteAccessCard } from '../components/settings/RemoteAccessCard';
import { BackupCard } from '../components/settings/BackupCard';
import { SystemInfoCard } from '../components/settings/SystemInfoCard';
import { StagingCard } from '../components/settings/StagingCard';
import { TestingCard } from '../components/settings/TestingCard';
import { NotificationsCard } from '../components/settings/NotificationsCard';
import { ExecutionSettingsCard } from '../components/settings/ExecutionSettingsCard';
import { SettingsNav, type NavSection } from '../components/settings/SettingsNav';
import { SettingsSection } from '../components/settings/SettingsSection';

export function Settings() {
  const t = useT();
  const { simpleMode, advancedMode } = useSettingsStore();
  const { data: sysInfo } = useSystemInfo();

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

  // Collapsible section state — all start closed except general
  const [openSections, setOpenSections] = useState<Record<string, boolean>>({});

  const toggleSection = useCallback((id: string) => {
    setOpenSections(prev => ({ ...prev, [id]: !prev[id] }));
  }, []);

  const handleNavigate = useCallback((id: string) => {
    // Open the target section when navigating via sidebar
    setOpenSections(prev => ({ ...prev, [id]: true }));
  }, []);

  const sections: NavSection[] = [
    { id: 'general', label: t.settings.settingsNavGeneral, visible: true },
    { id: 'execution', label: t.settings.settingsNavExecution, visible: !simpleMode },
    { id: 'infrastructure', label: t.settings.settingsNavInfrastructure, visible: !simpleMode },
    { id: 'environments', label: t.settings.settingsNavEnvironments, visible: advancedMode },
    { id: 'system', label: t.settings.settingsNavSystem, visible: true },
  ];

  return (
    <div className="animate-fade-in max-w-4xl">
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-text">{t.settings.title}</h1>
        <p className="text-sm text-text-secondary mt-1">{t.settings.subtitle}</p>
      </div>

      <div className="flex gap-8">
        <SettingsNav sections={sections} onNavigate={handleNavigate} />

        <div className="flex-1 min-w-0 space-y-10">
          <SettingsSection id="general" title={t.settings.settingsNavGeneral}>
            <GeneralSettingsCard />
            <AppearanceCard />
            <SecurityCard />
          </SettingsSection>

          {!simpleMode && (
            <SettingsSection id="execution" title={t.settings.settingsNavExecution} collapsible open={!!openSections['execution']} onToggle={() => toggleSection('execution')}>
              <ExecutionSettingsCard />
            </SettingsSection>
          )}

          {!simpleMode && (
            <SettingsSection id="infrastructure" title={t.settings.settingsNavInfrastructure} collapsible open={!!openSections['infrastructure']} onToggle={() => toggleSection('infrastructure')}>
              <RemoteAccessCard />
              <BackupCard />
              <NotificationsCard />
            </SettingsSection>
          )}

          {advancedMode && (
            <SettingsSection id="environments" title={t.settings.settingsNavEnvironments} collapsible open={!!openSections['environments']} onToggle={() => toggleSection('environments')}>
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
            </SettingsSection>
          )}

          <SettingsSection id="system" title={t.settings.settingsNavSystem} collapsible open={!!openSections['system']} onToggle={() => toggleSection('system')}>
            <SystemInfoCard />
          </SettingsSection>
        </div>
      </div>
    </div>
  );
}
