import { useState, useMemo, lazy, Suspense } from 'react';
import { useT } from '../i18n';
import {
  useOnboardingProfiles,
  useCreateOnboardingProfile,
  useUpdateOnboardingProfile,
  useDeleteOnboardingProfile,
  useResetOnboardingProfile,
  useOnboardingProfileHistory,
  type OnboardingProfile,
} from '../hooks/useOnboardingProfiles';
import { Card } from '../components/ui/Card';
import { Button } from '../components/ui/Button';
import { Input } from '../components/ui/Input';
import { Modal } from '../components/ui/Modal';
import { ModalFooter } from '../components/ui/ModalFooter';
import { Spinner } from '../components/ui/Spinner';
import { Plus, Trash2, RotateCcw, Save, BookOpen, Clock, Shield } from 'lucide-react';
import { useI18nStore } from '../i18n';
import { ensureLocalMonaco } from '../components/shared/monacoSetup';
import { Hint } from '../components/hints/Hint';

// Monaco from our own bundle, never the CDN (see monacoSetup)
const MonacoEditor = lazy(() =>
  ensureLocalMonaco().then(() => import('@monaco-editor/react')).then(m => ({ default: m.default }))
);

function resolveProfileName(profile: OnboardingProfile, locale: string): string {
  return profile.name[locale] || profile.name['en'] || profile.key;
}

/** Languages a profile's content is written in; a missing one falls back to the other when rendered. */
const CONTENT_LANGUAGES = ['de', 'en'] as const;
type ContentLanguage = (typeof CONTENT_LANGUAGES)[number];
type ContentByLanguage = Record<ContentLanguage, string>;

function contentOf(profile: OnboardingProfile): ContentByLanguage {
  return { de: profile.content?.de ?? '', en: profile.content?.en ?? '' };
}

export function OnboardingProfiles() {
  const t = useT();
  const locale = useI18nStore(s => s.locale);
  const { data: profiles, isLoading } = useOnboardingProfiles();
  const createProfile = useCreateOnboardingProfile();
  const updateProfile = useUpdateOnboardingProfile();
  const deleteProfile = useDeleteOnboardingProfile();
  const resetProfile = useResetOnboardingProfile();

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [showDelete, setShowDelete] = useState(false);
  const [showReset, setShowReset] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [editContent, setEditContent] = useState<ContentByLanguage>({ de: '', en: '' });
  const [contentLanguage, setContentLanguage] = useState<ContentLanguage>(locale === 'de' ? 'de' : 'en');
  const [editName, setEditName] = useState({ de: '', en: '' });
  const [editDescription, setEditDescription] = useState({ de: '', en: '' });
  const [createForm, setCreateForm] = useState({ key: '', nameDe: '', nameEn: '', content: '' });
  const [hasChanges, setHasChanges] = useState(false);

  const filtered = useMemo(() => {
    if (!profiles) return [];
    if (!search) return profiles;
    const q = search.toLowerCase();
    return profiles.filter(p =>
      p.key.includes(q) ||
      resolveProfileName(p, locale).toLowerCase().includes(q)
    );
  }, [profiles, search, locale]);

  const selected = useMemo(
    () => profiles?.find(p => p.id === selectedId) ?? null,
    [profiles, selectedId]
  );

  const handleSelect = (profile: OnboardingProfile) => {
    setSelectedId(profile.id);
    setEditContent(contentOf(profile));
    setEditName({ de: profile.name.de || '', en: profile.name.en || '' });
    setEditDescription({ de: profile.description?.de || '', en: profile.description?.en || '' });
    setHasChanges(false);
  };

  const handleSave = () => {
    if (!selected) return;
    updateProfile.mutate({
      id: selected.id,
      data: {
        name: editName,
        description: editDescription,
        content: editContent,
      },
    }, {
      onSuccess: () => setHasChanges(false),
    });
  };

  const handleCreate = () => {
    createProfile.mutate({
      key: createForm.key,
      name: { de: createForm.nameDe, en: createForm.nameEn },
      description: { de: '', en: '' },
      content: {
        de: '## ' + (createForm.nameDe || createForm.nameEn) + '\n\n',
        en: '## ' + createForm.nameEn + '\n\n',
      },
      type: 'PROFILE',
    }, {
      onSuccess: () => {
        setShowCreate(false);
        setCreateForm({ key: '', nameDe: '', nameEn: '', content: '' });
      },
    });
  };

  const handleDelete = () => {
    if (!selected) return;
    deleteProfile.mutate(selected.id, {
      onSuccess: () => {
        setSelectedId(null);
        setShowDelete(false);
      },
    });
  };

  const handleReset = () => {
    if (!selected) return;
    resetProfile.mutate(selected.id, {
      onSuccess: () => setShowReset(false),
    });
  };

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
        <div className="lg:col-span-1 space-y-3">
          <Input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder={t.common.search}
          />
          <div className="space-y-2">
            {filtered.map(profile => (
              <Card
                key={profile.id}
                className={`cursor-pointer transition-all p-3 ${
                  selectedId === profile.id ? 'ring-2 ring-accent' : 'hover:bg-surface-raised'
                }`}
                onClick={() => handleSelect(profile)}
              >
                <div className="flex items-center justify-between">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <BookOpen className="w-4 h-4 text-text-tertiary flex-shrink-0" />
                      <span className="font-medium text-sm text-text truncate">
                        {resolveProfileName(profile, locale)}
                      </span>
                    </div>
                    <div className="flex items-center gap-2 mt-1 ml-6">
                      <span className="text-xs text-text-tertiary font-mono">{profile.key}</span>
                      <span className={`text-xs px-1.5 py-0.5 rounded ${
                        profile.type === 'BASE'
                          ? 'bg-blue-500/10 text-blue-400'
                          : 'bg-surface-raised text-text-tertiary'
                      }`}>
                        {profile.type === 'BASE' ? t.onboardingProfiles.typeBase : t.onboardingProfiles.typeProfile}
                      </span>
                      {profile.builtIn && (
                        <span className="text-xs px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-400 flex items-center gap-0.5">
                          <Shield className="w-3 h-3" />
                          {t.onboardingProfiles.builtIn}
                        </span>
                      )}
                    </div>
                  </div>
                  <span className="text-xs text-text-tertiary">v{profile.version}</span>
                </div>
              </Card>
            ))}
            {filtered.length === 0 && (
              <p className="text-sm text-text-tertiary text-center py-8">{t.onboardingProfiles.noProfiles}</p>
            )}
          </div>
        </div>

        {/* Right: Editor */}
        <div className="lg:col-span-2">
          {selected ? (
            <Card className="p-4 space-y-4">
              {/* Header */}
              <div className="flex items-center justify-between">
                <div>
                  <h2 className="text-lg font-semibold text-text">
                    {resolveProfileName(selected, locale)}
                  </h2>
                  <span className="text-xs text-text-tertiary font-mono">{selected.key}</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-xs text-text-tertiary">v{selected.version}</span>
                  {selected.historyCount > 0 && (
                    <Button
                      size="sm"
                      variant="ghost"
                      icon={<Clock className="w-3.5 h-3.5" />}
                      onClick={() => setShowHistory(true)}
                    >
                      {t.onboardingProfiles.history} ({selected.historyCount})
                    </Button>
                  )}
                </div>
              </div>

              {/* Name fields */}
              <div className="grid grid-cols-2 gap-3">
                <Input
                  label="Name (DE)"
                  value={editName.de}
                  onChange={e => { setEditName(n => ({ ...n, de: e.target.value })); setHasChanges(true); }}
                />
                <Input
                  label="Name (EN)"
                  value={editName.en}
                  onChange={e => { setEditName(n => ({ ...n, en: e.target.value })); setHasChanges(true); }}
                />
              </div>

              {/* Content editor, one language at a time */}
              <div>
                <div className="flex flex-wrap items-center justify-between gap-2 mb-1.5">
                  <span className="text-xs font-medium text-text-secondary">{t.onboardingProfiles.content}</span>
                  <div role="group" aria-label={t.onboardingProfiles.contentLanguage} className="inline-flex rounded-lg border border-border p-0.5">
                    {CONTENT_LANGUAGES.map(lang => (
                      <button
                        key={lang}
                        type="button"
                        aria-pressed={contentLanguage === lang}
                        onClick={() => setContentLanguage(lang)}
                        className={`px-2.5 py-0.5 rounded-md text-xs font-medium transition-colors ${
                          contentLanguage === lang ? 'bg-accent text-text-inverse' : 'text-text-secondary hover:text-text'
                        }`}
                      >
                        {lang.toUpperCase()}
                      </button>
                    ))}
                  </div>
                </div>
                <p className="text-xs text-text-tertiary mb-1.5">{t.onboardingProfiles.contentFallback}</p>
                <div className="border border-border rounded-lg overflow-hidden" style={{ height: '400px' }}>
                  <Suspense fallback={<div className="flex items-center justify-center h-full"><Spinner /></div>}>
                    <MonacoEditor
                      height="400px"
                      language="markdown"
                      theme="vs-dark"
                      path={`onboarding-profile-${selected.id}-${contentLanguage}.md`}
                      value={editContent[contentLanguage]}
                      onChange={value => { setEditContent(c => ({ ...c, [contentLanguage]: value || '' })); setHasChanges(true); }}
                      options={{
                        minimap: { enabled: false },
                        wordWrap: 'on',
                        fontSize: 13,
                        lineNumbers: 'off',
                        scrollBeyondLastLine: false,
                      }}
                    />
                  </Suspense>
                </div>
              </div>

              {/* Actions */}
              <div className="flex items-center justify-between pt-2">
                <div className="flex items-center gap-2">
                  {selected.builtIn && (
                    <Button
                      size="sm"
                      variant="secondary"
                      icon={<RotateCcw className="w-3.5 h-3.5" />}
                      onClick={() => setShowReset(true)}
                    >
                      {t.onboardingProfiles.reset}
                    </Button>
                  )}
                  {!selected.builtIn && (
                    <Button
                      size="sm"
                      variant="danger"
                      icon={<Trash2 className="w-3.5 h-3.5" />}
                      onClick={() => setShowDelete(true)}
                    >
                      {t.common.delete}
                    </Button>
                  )}
                </div>
                <Button
                  icon={<Save className="w-4 h-4" />}
                  onClick={handleSave}
                  loading={updateProfile.isPending}
                  disabled={!hasChanges}
                >
                  {t.common.save}
                </Button>
              </div>
            </Card>
          ) : (
            <Card className="p-8 text-center text-text-tertiary">
              <BookOpen className="w-8 h-8 mx-auto mb-2 opacity-50" />
              <p>{t.onboardingProfiles.selectProfile}</p>
            </Card>
          )}
        </div>
      </div>

      {/* Create Modal */}
      <Modal
        open={showCreate}
        onClose={() => setShowCreate(false)}
        title={t.onboardingProfiles.create}
        footer={
          <ModalFooter
            onCancel={() => setShowCreate(false)}
            onAction={handleCreate}
            cancelLabel={t.common.cancel}
            actionLabel={t.common.create}
            actionLoading={createProfile.isPending}
            actionDisabled={!createForm.key || !createForm.nameEn}
          />
        }
      >
        <div className="space-y-4">
          <Input
            label={t.onboardingProfiles.key}
            value={createForm.key}
            onChange={e => setCreateForm(f => ({ ...f, key: e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, '') }))}
            placeholder="my-profile"
            hint={t.onboardingProfiles.keyHint}
          />
          <div className="grid grid-cols-2 gap-3">
            <Input
              label="Name (DE)"
              value={createForm.nameDe}
              onChange={e => setCreateForm(f => ({ ...f, nameDe: e.target.value }))}
            />
            <Input
              label="Name (EN)"
              value={createForm.nameEn}
              onChange={e => setCreateForm(f => ({ ...f, nameEn: e.target.value }))}
            />
          </div>
        </div>
      </Modal>

      {/* Delete Confirmation */}
      <Modal
        open={showDelete}
        onClose={() => setShowDelete(false)}
        title={`${t.common.delete} "${selected ? resolveProfileName(selected, locale) : ''}"?`}
        size="sm"
        footer={
          <ModalFooter
            onCancel={() => setShowDelete(false)}
            onAction={handleDelete}
            cancelLabel={t.common.cancel}
            actionLabel={t.common.delete}
            actionVariant="danger"
            actionLoading={deleteProfile.isPending}
          />
        }
      >
        <p className="text-sm text-text-secondary">{t.onboardingProfiles.deleteConfirm}</p>
      </Modal>

      {/* Reset Confirmation */}
      <Modal
        open={showReset}
        onClose={() => setShowReset(false)}
        title={t.onboardingProfiles.reset}
        size="sm"
        footer={
          <ModalFooter
            onCancel={() => setShowReset(false)}
            onAction={handleReset}
            cancelLabel={t.common.cancel}
            actionLabel={t.onboardingProfiles.reset}
            actionLoading={resetProfile.isPending}
          />
        }
      >
        <p className="text-sm text-text-secondary">{t.onboardingProfiles.resetConfirm}</p>
      </Modal>

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

function HistoryModal({ profileId, open, onClose }: { profileId: string; open: boolean; onClose: () => void }) {
  const t = useT();
  const { data: history, isLoading } = useOnboardingProfileHistory(profileId);

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t.onboardingProfiles.history}
      size="lg"
      footer={<Button variant="secondary" onClick={onClose}>{t.common.close}</Button>}
    >
      {isLoading ? (
        <Spinner />
      ) : history && history.length > 0 ? (
        <div className="space-y-3 max-h-[60vh] overflow-y-auto">
          {history.map(h => (
            <Card key={h.id} className="p-3">
              <div className="flex items-center justify-between mb-2">
                <span className="text-sm font-medium text-text">v{h.version}</span>
                <div className="flex items-center gap-2 text-xs text-text-tertiary">
                  <span>{h.changedBy}</span>
                  <span>{new Date(h.changedAt).toLocaleString()}</span>
                </div>
              </div>
              {Object.entries(h.content ?? {}).map(([lang, text]) => (
                <div key={lang} className="mt-1">
                  <span className="text-[10px] font-semibold uppercase text-text-tertiary">{lang}</span>
                  <pre className="text-xs text-text-secondary whitespace-pre-wrap font-mono bg-surface rounded p-2 max-h-32 overflow-y-auto">
                    {text}
                  </pre>
                </div>
              ))}
            </Card>
          ))}
        </div>
      ) : (
        <p className="text-sm text-text-tertiary text-center py-4">{t.onboardingProfiles.historyEmpty}</p>
      )}
    </Modal>
  );
}
