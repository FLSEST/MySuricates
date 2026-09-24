import { Trash2, RotateCcw, Save, Clock, ArrowUpCircle } from 'lucide-react';
import { Card } from '../ui/Card';
import { Button } from '../ui/Button';
import { Input } from '../ui/Input';
import { MonacoEditor } from '../shared/MonacoEditor';
import { useI18nStore, useT } from '../../i18n';
import type { OnboardingProfile } from '../../hooks/useOnboardingProfiles';
import type { OnboardingProfileEditor } from '../../hooks/useOnboardingProfileEditor';
import { resolveProfileName } from '../../utils/onboardingProfileText';
import { ContentLanguageToggle } from './ContentLanguageToggle';

/**
 * Editor of the selected profile: names and descriptions (DE/EN), the content one language at a
 * time, and the actions — history, shipped text, reset (built-in) or delete (own), save.
 */
export function ProfileEditor({ profile, editor, onShowHistory, onShowShipped, onReset, onDelete }: {
  profile: OnboardingProfile;
  editor: OnboardingProfileEditor;
  onShowHistory: () => void;
  onShowShipped: () => void;
  onReset: () => void;
  onDelete: () => void;
}) {
  const t = useT();
  const locale = useI18nStore(s => s.locale);
  const { draft, contentLanguage } = editor;

  return (
    <Card className="p-4 space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold text-text">
            {resolveProfileName(profile, locale)}
          </h2>
          <span className="text-xs text-text-tertiary font-mono">{profile.key}</span>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs text-text-tertiary">v{profile.version}</span>
          {profile.historyCount > 0 && (
            <Button
              size="sm"
              variant="ghost"
              icon={<Clock className="w-3.5 h-3.5" />}
              onClick={onShowHistory}
            >
              {t.onboardingProfiles.history} ({profile.historyCount})
            </Button>
          )}
        </div>
      </div>

      {profile.updateAvailable && (
        <div role="status" className="flex flex-wrap items-start gap-3 rounded-lg border border-info/30 bg-info-subtle px-3 py-2">
          <ArrowUpCircle className="w-4 h-4 mt-0.5 shrink-0 text-info" />
          <p className="flex-1 min-w-[12rem] text-sm text-text-secondary">
            {t.onboardingProfiles.updateAvailableHint}
          </p>
          <Button size="sm" variant="secondary" onClick={onShowShipped}>
            {t.onboardingProfiles.showShipped}
          </Button>
        </div>
      )}

      {/* Name and description fields */}
      <div className="grid grid-cols-2 gap-3">
        <Input
          label={t.onboardingProfiles.nameDe}
          value={draft.name.de}
          onChange={e => editor.setName('de', e.target.value)}
        />
        <Input
          label={t.onboardingProfiles.nameEn}
          value={draft.name.en}
          onChange={e => editor.setName('en', e.target.value)}
        />
        <Input
          label={t.onboardingProfiles.descriptionDe}
          value={draft.description.de}
          onChange={e => editor.setDescription('de', e.target.value)}
        />
        <Input
          label={t.onboardingProfiles.descriptionEn}
          value={draft.description.en}
          onChange={e => editor.setDescription('en', e.target.value)}
        />
      </div>

      {/* Content editor, one language at a time */}
      <div>
        <div className="flex flex-wrap items-center justify-between gap-2 mb-1.5">
          <span className="text-xs font-medium text-text-secondary">{t.onboardingProfiles.content}</span>
          <ContentLanguageToggle value={contentLanguage} onChange={editor.setContentLanguage} />
        </div>
        <p className="text-xs text-text-tertiary mb-1.5">{t.onboardingProfiles.contentFallback}</p>
        {/* Through the wrapper (87c5e9f3): no lost keystrokes, Enter breaks the line; one model
            per profile and language keeps each its own undo history. */}
        <MonacoEditor
          height="400px"
          language="markdown"
          prose
          lineNumbers="off"
          path={`onboarding-profile-${profile.id}-${contentLanguage}.md`}
          value={draft.content[contentLanguage]}
          onChange={editor.setContent}
        />
      </div>

      {/* Actions */}
      <div className="flex items-center justify-between pt-2">
        <div className="flex items-center gap-2">
          {profile.builtIn && (
            <Button
              size="sm"
              variant="secondary"
              icon={<RotateCcw className="w-3.5 h-3.5" />}
              onClick={onReset}
            >
              {t.onboardingProfiles.reset}
            </Button>
          )}
          {!profile.builtIn && (
            <Button
              size="sm"
              variant="danger"
              icon={<Trash2 className="w-3.5 h-3.5" />}
              onClick={onDelete}
            >
              {t.common.delete}
            </Button>
          )}
        </div>
        <Button
          icon={<Save className="w-4 h-4" />}
          onClick={editor.save}
          loading={editor.saving}
          disabled={!editor.hasChanges}
        >
          {t.common.save}
        </Button>
      </div>
    </Card>
  );
}
