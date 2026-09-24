import { useMemo, useState } from 'react';
import { useI18nStore } from '../i18n';
import {
  useUpdateOnboardingProfile,
  useDeleteOnboardingProfile,
  useResetOnboardingProfile,
  type OnboardingProfile,
} from './useOnboardingProfiles';
import { contentOf, type ContentByLanguage, type ContentLanguage } from '../components/onboarding/onboardingProfileText';

type Bilingual = { de: string; en: string };

/**
 * Bearbeitungszustand der Seite Onboarding-Profile: das ausgewählte Profil (aus der Live-Liste),
 * der Entwurf von Name, Beschreibung und Inhalt je Sprache, die Sprache, deren Inhalt gerade
 * bearbeitet wird, `hasChanges` sowie Speichern, Löschen und das Zurücksetzen auf den
 * ausgelieferten Text. Eine Auswahl lädt den Entwurf aus dem Profil und verwirft ungespeicherte
 * Änderungen; nach dem Zurücksetzen zeigt der Editor den übernommenen Text.
 */
export function useOnboardingProfileEditor(profiles: OnboardingProfile[] | undefined) {
  const locale = useI18nStore(s => s.locale);
  const updateProfile = useUpdateOnboardingProfile();
  const deleteProfile = useDeleteOnboardingProfile();
  const resetProfile = useResetOnboardingProfile();

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [editContent, setEditContent] = useState<ContentByLanguage>({ de: '', en: '' });
  const [contentLanguage, setContentLanguage] = useState<ContentLanguage>(locale === 'de' ? 'de' : 'en');
  const [editName, setEditName] = useState<Bilingual>({ de: '', en: '' });
  const [editDescription, setEditDescription] = useState<Bilingual>({ de: '', en: '' });
  const [hasChanges, setHasChanges] = useState(false);

  const selected = useMemo(
    () => profiles?.find(p => p.id === selectedId) ?? null,
    [profiles, selectedId]
  );

  const select = (profile: OnboardingProfile) => {
    setSelectedId(profile.id);
    setEditContent(contentOf(profile));
    setEditName({ de: profile.name.de || '', en: profile.name.en || '' });
    setEditDescription({ de: profile.description?.de || '', en: profile.description?.en || '' });
    setHasChanges(false);
  };

  const setName = (lang: keyof Bilingual, value: string) => {
    setEditName(n => ({ ...n, [lang]: value }));
    setHasChanges(true);
  };

  const setDescription = (lang: keyof Bilingual, value: string) => {
    setEditDescription(d => ({ ...d, [lang]: value }));
    setHasChanges(true);
  };

  /** Content of the language being edited (`contentLanguage`). */
  const setContent = (value: string) => {
    setEditContent(c => ({ ...c, [contentLanguage]: value }));
    setHasChanges(true);
  };

  const save = () => {
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

  /** Deletes the selected profile; `onDone` closes the confirmation. */
  const remove = (onDone: () => void) => {
    if (!selected) return;
    deleteProfile.mutate(selected.id, {
      onSuccess: () => {
        setSelectedId(null);
        onDone();
      },
    });
  };

  /** Takes over the shipped text of the selected built-in profile; `onDone` closes the dialogs. */
  const resetToShipped = (onDone: () => void) => {
    if (!selected) return;
    resetProfile.mutate(selected.id, {
      onSuccess: (profile) => {
        onDone();
        // The editor shows the taken-over text, not the one it had before.
        if (profile) select(profile);
      },
    });
  };

  return {
    selected,
    selectedId,
    select,
    draft: { name: editName, description: editDescription, content: editContent },
    contentLanguage,
    setContentLanguage,
    setName,
    setDescription,
    setContent,
    hasChanges,
    save,
    saving: updateProfile.isPending,
    remove,
    removing: deleteProfile.isPending,
    resetToShipped,
    resetting: resetProfile.isPending,
  };
}

export type OnboardingProfileEditor = ReturnType<typeof useOnboardingProfileEditor>;
