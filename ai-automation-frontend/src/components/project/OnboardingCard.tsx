import { useState, useEffect } from 'react';
import { useT } from '../../i18n';
import { useOnboardingStatus, useEnableOnboarding, useDisableOnboarding, useRefreshOnboarding, useOnboardingPreview } from '../../hooks/useProjects';
import { useOnboardingProfiles, type OnboardingProfile } from '../../hooks/useOnboardingProfiles';
import { Card, CardHeader } from '../ui/Card';
import { Button } from '../ui/Button';
import { Modal } from '../ui/Modal';
import { RefreshCw, Eye, Check, X } from 'lucide-react';
import { useI18nStore } from '../../i18n';

interface OnboardingCardProps {
  projectId: string;
}

function resolveProfileName(profile: OnboardingProfile, locale: string): string {
  return profile.name[locale] || profile.name['en'] || profile.key;
}

export function OnboardingCard({ projectId }: OnboardingCardProps) {
  const t = useT();
  const locale = useI18nStore(s => s.locale);
  const { data: status, isLoading: statusLoading } = useOnboardingStatus(projectId);
  const { data: profiles } = useOnboardingProfiles();
  const enableOnboarding = useEnableOnboarding();
  const disableOnboarding = useDisableOnboarding();
  const refreshOnboarding = useRefreshOnboarding();
  const { data: previewData, refetch: fetchPreview } = useOnboardingPreview(projectId);

  const [showPreview, setShowPreview] = useState(false);
  const [selectedFormat, setSelectedFormat] = useState('CLAUDE_RULES');
  const [selectedProfileIds, setSelectedProfileIds] = useState<string[]>([]);

  const enabled = status?.enabled ?? false;
  const availableProfiles = profiles?.filter(p => p.type === 'PROFILE') ?? [];

  useEffect(() => {
    if (status) {
      setSelectedFormat(status.format || 'CLAUDE_RULES');
      setSelectedProfileIds(status.profileIds || []);
    }
  }, [status]);

  const handleToggle = () => {
    if (enabled) {
      disableOnboarding.mutate(projectId);
    } else {
      enableOnboarding.mutate({
        id: projectId,
        config: { format: selectedFormat, profileIds: selectedProfileIds },
      });
    }
  };

  const handleProfileToggle = (profileId: string) => {
    const newIds = selectedProfileIds.includes(profileId)
      ? selectedProfileIds.filter(id => id !== profileId)
      : [...selectedProfileIds, profileId];
    setSelectedProfileIds(newIds);

    if (enabled) {
      enableOnboarding.mutate({
        id: projectId,
        config: { format: selectedFormat, profileIds: newIds },
      });
    }
  };

  const handleFormatChange = (format: string) => {
    setSelectedFormat(format);
    if (enabled) {
      enableOnboarding.mutate({
        id: projectId,
        config: { format, profileIds: selectedProfileIds },
      });
    }
  };

  const handlePreview = () => {
    fetchPreview();
    setShowPreview(true);
  };

  if (statusLoading) return null;

  return (
    <>
      <Card>
        <CardHeader
          title={t.projects.onboarding.title}
          action={
            <button
              onClick={handleToggle}
              className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
                enabled ? 'bg-accent' : 'bg-border'
              }`}
              disabled={enableOnboarding.isPending || disableOnboarding.isPending}
            >
              <span
                className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
                  enabled ? 'translate-x-6' : 'translate-x-1'
                }`}
              />
            </button>
          }
        />

        <p className="text-xs text-text-tertiary mb-3">{t.projects.onboarding.hint}</p>

        {enabled && (
          <div className="space-y-3">
            {/* Profile selection */}
            <div>
              <label className="text-xs font-medium text-text-secondary mb-1.5 block">
                {t.projects.onboarding.profiles}
              </label>
              <div className="flex flex-wrap gap-1.5">
                {availableProfiles.map(profile => {
                  const isSelected = selectedProfileIds.includes(profile.id);
                  return (
                    <button
                      key={profile.id}
                      onClick={() => handleProfileToggle(profile.id)}
                      className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-medium transition-colors ${
                        isSelected
                          ? 'bg-accent/15 text-accent border border-accent/30'
                          : 'bg-surface-raised text-text-secondary border border-border hover:border-text-tertiary'
                      }`}
                    >
                      {isSelected && <Check className="w-3 h-3" />}
                      {resolveProfileName(profile, locale)}
                    </button>
                  );
                })}
                {availableProfiles.length === 0 && (
                  <span className="text-xs text-text-tertiary">{t.projects.onboarding.noProfiles}</span>
                )}
              </div>
            </div>

            {/* Format selection */}
            <div>
              <label className="text-xs font-medium text-text-secondary mb-1.5 block">
                {t.projects.onboarding.format}
              </label>
              <select
                value={selectedFormat}
                onChange={e => handleFormatChange(e.target.value)}
                className="text-sm bg-surface border border-border rounded-lg px-3 py-1.5 text-text"
              >
                <option value="CLAUDE_RULES">{t.projects.onboarding.formatClaudeRules}</option>
                <option value="AGENTS_MD">{t.projects.onboarding.formatAgentsMd}</option>
              </select>
            </div>

            {/* Status & Actions */}
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2 text-xs">
                {status?.fileExists ? (
                  <span className="flex items-center gap-1 text-green-500">
                    <Check className="w-3.5 h-3.5" />
                    {t.projects.onboarding.installed}
                  </span>
                ) : (
                  <span className="flex items-center gap-1 text-text-tertiary">
                    <X className="w-3.5 h-3.5" />
                    {t.projects.onboarding.notInstalled}
                  </span>
                )}
                {status?.filePath && (
                  <span className="text-text-tertiary font-mono">{status.filePath}</span>
                )}
              </div>
              <div className="flex items-center gap-1.5">
                <Button
                  size="sm"
                  variant="ghost"
                  icon={<RefreshCw className="w-3.5 h-3.5" />}
                  onClick={() => refreshOnboarding.mutate(projectId)}
                  loading={refreshOnboarding.isPending}
                  title={t.projects.onboarding.refresh}
                />
                <Button
                  size="sm"
                  variant="ghost"
                  icon={<Eye className="w-3.5 h-3.5" />}
                  onClick={handlePreview}
                  title={t.projects.onboarding.preview}
                />
              </div>
            </div>
          </div>
        )}
      </Card>

      {/* Preview Modal */}
      <Modal
        open={showPreview}
        onClose={() => setShowPreview(false)}
        title={t.projects.onboarding.preview}
        size="lg"
        footer={
          <Button variant="secondary" onClick={() => setShowPreview(false)}>
            {t.common.close}
          </Button>
        }
      >
        <pre className="text-sm text-text-secondary whitespace-pre-wrap font-mono bg-surface rounded-lg p-4 max-h-[60vh] overflow-y-auto">
          {previewData?.content || '...'}
        </pre>
      </Modal>
    </>
  );
}
