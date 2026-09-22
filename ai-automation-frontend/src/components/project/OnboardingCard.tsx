import { useState } from 'react';
import { useSyncedState } from '../../hooks/useSyncedState';
import { useT } from '../../i18n';
import { useOnboardingStatus, useEnableOnboarding, useDisableOnboarding, useRefreshOnboarding, useOnboardingPreview } from '../../hooks/useProjects';
import { useOnboardingProfiles, type OnboardingProfile } from '../../hooks/useOnboardingProfiles';
import { Card, CardHeader } from '../ui/Card';
import { Button } from '../ui/Button';
import { Modal } from '../ui/Modal';
import { RefreshCw, Eye, Check, X, AlertTriangle } from 'lucide-react';
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
  // Form state follows the server status; local edits survive until the status object changes.
  const [selectedFormat, setSelectedFormat] = useSyncedState<string, typeof status>(
    status, (s, prev) => (s ? s.format || 'CLAUDE_RULES' : prev ?? 'CLAUDE_RULES'),
  );
  const [selectedProfileIds, setSelectedProfileIds] = useSyncedState<string[], typeof status>(
    status, (s, prev) => (s ? s.profileIds || [] : prev ?? []),
  );
  // Language of the written text: the project's, or the UI language when onboarding is switched on.
  const [selectedLanguage, setSelectedLanguage] = useSyncedState<string, typeof status>(
    status, (s, prev) => (s?.enabled && s.language ? s.language : prev ?? locale),
  );

  const enabled = status?.enabled ?? false;
  const availableProfiles = profiles?.filter(p => p.type === 'PROFILE') ?? [];

  const handleToggle = () => {
    if (enabled) {
      disableOnboarding.mutate(projectId);
    } else {
      enableOnboarding.mutate({
        id: projectId,
        config: { format: selectedFormat, profileIds: selectedProfileIds, language: selectedLanguage },
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
        config: { format: selectedFormat, profileIds: newIds, language: selectedLanguage },
      });
    }
  };

  const handleFormatChange = (format: string) => {
    setSelectedFormat(format);
    if (enabled) {
      enableOnboarding.mutate({
        id: projectId,
        config: { format, profileIds: selectedProfileIds, language: selectedLanguage },
      });
    }
  };

  const handleLanguageChange = (language: string) => {
    setSelectedLanguage(language);
    if (enabled) {
      enableOnboarding.mutate({
        id: projectId,
        config: { format: selectedFormat, profileIds: selectedProfileIds, language },
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
              type="button"
              role="switch"
              aria-checked={enabled}
              aria-label={t.projects.onboarding.title}
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

            {/* Format and language */}
            <div className="flex flex-wrap gap-4">
              <div>
                <label htmlFor="onboarding-format" className="text-xs font-medium text-text-secondary mb-1.5 block">
                  {t.projects.onboarding.format}
                </label>
                <select
                  id="onboarding-format"
                  value={selectedFormat}
                  onChange={e => handleFormatChange(e.target.value)}
                  className="text-sm bg-surface border border-border rounded-lg px-3 py-1.5 text-text"
                >
                  <option value="CLAUDE_RULES">{t.projects.onboarding.formatClaudeRules}</option>
                  <option value="AGENTS_MD">{t.projects.onboarding.formatAgentsMd}</option>
                </select>
              </div>
              <div>
                <label htmlFor="onboarding-language" className="text-xs font-medium text-text-secondary mb-1.5 block">
                  {t.projects.onboarding.language}
                </label>
                <select
                  id="onboarding-language"
                  value={selectedLanguage}
                  onChange={e => handleLanguageChange(e.target.value)}
                  className="text-sm bg-surface border border-border rounded-lg px-3 py-1.5 text-text"
                >
                  <option value="de">Deutsch</option>
                  <option value="en">English</option>
                </select>
              </div>
            </div>

            {status?.fileForeign && (
              <div role="alert" className="flex items-start gap-2 rounded-lg border border-warning/30 bg-warning/10 px-3 py-2 text-xs text-warning">
                <AlertTriangle className="w-3.5 h-3.5 mt-0.5 shrink-0" />
                <span>{t.projects.onboarding.fileForeign.replace('{path}', () => status.filePath)}</span>
              </div>
            )}

            {/* Status & Actions */}
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2 text-xs">
                {status?.fileExists && !status.fileForeign ? (
                  <span className="flex items-center gap-1 text-success">
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
