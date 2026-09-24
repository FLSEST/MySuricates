import { useState } from 'react';
import { useT } from '../i18n';
import { useOnboardingProfiles } from '../hooks/useOnboardingProfiles';
import { useOnboardingProfileEditor } from '../hooks/useOnboardingProfileEditor';
import { Card } from '../components/ui/Card';
import { Button } from '../components/ui/Button';
import { ConfirmModal } from '../components/ui/ConfirmModal';
import { Spinner } from '../components/ui/Spinner';
import { Plus, BookOpen } from 'lucide-react';
import { useI18nStore } from '../i18n';
import { Hint } from '../components/hints/Hint';
import { resolveProfileName } from '../components/onboarding/onboardingProfileText';
import { ProfileList } from '../components/onboarding/ProfileList';
import { ProfileEditor } from '../components/onboarding/ProfileEditor';
import { CreateProfileModal } from '../components/onboarding/CreateProfileModal';
import { ShippedModal } from '../components/onboarding/ShippedModal';
import { HistoryModal } from '../components/onboarding/HistoryModal';

export function OnboardingProfiles() {
  const t = useT();
  const locale = useI18nStore(s => s.locale);
  const { data: profiles, isLoading } = useOnboardingProfiles();
  const editor = useOnboardingProfileEditor(profiles);
  const { selected } = editor;

  const [showCreate, setShowCreate] = useState(false);
  const [showDelete, setShowDelete] = useState(false);
  const [showReset, setShowReset] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [showShipped, setShowShipped] = useState(false);

  const handleReset = () => editor.resetToShipped(() => {
    setShowReset(false);
    setShowShipped(false);
  });

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Spinner text={t.common.loading} />
      </div>
    );
  }

  return (
    <div className="space-y-6 animate-fade-in">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-text">{t.onboardingProfiles.title}</h1>
          <p className="text-sm text-text-tertiary mt-1">{t.onboardingProfiles.subtitle}</p>
        </div>
        <Button icon={<Plus className="w-4 h-4" />} onClick={() => setShowCreate(true)}>
          {t.onboardingProfiles.create}
        </Button>
      </div>

      <Hint id="onboarding-profiles" />

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Left: Profile List */}
        <ProfileList profiles={profiles} selectedId={editor.selectedId} onSelect={editor.select} />

        {/* Right: Editor */}
        <div className="lg:col-span-2">
          {selected ? (
            <ProfileEditor
              profile={selected}
              editor={editor}
              onShowHistory={() => setShowHistory(true)}
              onShowShipped={() => setShowShipped(true)}
              onReset={() => setShowReset(true)}
              onDelete={() => setShowDelete(true)}
            />
          ) : (
            <Card className="p-8 text-center text-text-tertiary">
              <BookOpen className="w-8 h-8 mx-auto mb-2 opacity-50" />
              <p>{t.onboardingProfiles.selectProfile}</p>
            </Card>
          )}
        </div>
      </div>

      <CreateProfileModal open={showCreate} onClose={() => setShowCreate(false)} />

      {/* Delete Confirmation */}
      <ConfirmModal
        open={showDelete}
        onClose={() => setShowDelete(false)}
        onConfirm={() => editor.remove(() => setShowDelete(false))}
        title={`${t.common.delete} "${selected ? resolveProfileName(selected, locale) : ''}"?`}
        message={t.onboardingProfiles.deleteConfirm}
        confirmLabel={t.common.delete}
        variant="danger"
        isPending={editor.removing}
      />

      {/* Reset Confirmation */}
      <ConfirmModal
        open={showReset}
        onClose={() => setShowReset(false)}
        onConfirm={handleReset}
        title={t.onboardingProfiles.reset}
        message={t.onboardingProfiles.resetConfirm}
        confirmLabel={t.onboardingProfiles.reset}
        isPending={editor.resetting}
      />

      {/* Shipped text of a built-in profile with an update */}
      {selected && showShipped && (
        <ShippedModal
          profileId={selected.id}
          open={showShipped}
          onClose={() => setShowShipped(false)}
          onTakeOver={handleReset}
          takingOver={editor.resetting}
        />
      )}

      {/* History Modal */}
      {selected && showHistory && (
        <HistoryModal
          profileId={selected.id}
          open={showHistory}
          onClose={() => setShowHistory(false)}
        />
      )}
    </div>
  );
}
