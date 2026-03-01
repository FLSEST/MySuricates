import { useSettingsStore } from '../stores/settingsStore';
import { useT } from '../i18n';
import { useSystemInfo } from '../hooks/useSystem';
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

export function Settings() {
  const t = useT();
  const { advancedMode } = useSettingsStore();
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

  return (
    <div className="space-y-6 animate-fade-in max-w-3xl">
      <div>
        <h1 className="text-2xl font-bold text-text">{t.settings.title}</h1>
        <p className="text-sm text-text-secondary mt-1">{t.settings.subtitle}</p>
      </div>

      <GeneralSettingsCard />
      <AppearanceCard />
      <SecurityCard />
      <RemoteAccessCard />
      <BackupCard />

      {advancedMode && (
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
      )}

      {advancedMode && (
        <TestingCard
          testing={testing}
          devVersion={sysInfo?.version}
          onStart={() => testingStart.mutate()}
          onStop={() => testingStop.mutate()}
          onReset={() => testingReset.mutateAsync()}
          onSyncAll={() => testingSync.mutateAsync(undefined)}
          onSyncSelective={(req) => testingSync.mutateAsync(req)}
          onPullChanges={() => testingPullChanges.mutateAsync()}
          syncResult={testingSync.data}
          pullResult={testingPullChanges.data}
          startLoading={testingStart.isPending}
          stopLoading={testingStop.isPending}
          resetLoading={testingReset.isPending}
          syncLoading={testingSync.isPending}
          pullLoading={testingPullChanges.isPending}
        />
      )}

      <SystemInfoCard />
    </div>
  );
}
