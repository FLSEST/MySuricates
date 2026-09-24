import { useMemo, useState } from 'react';
import { BookOpen, Shield, ArrowUpCircle, PencilLine } from 'lucide-react';
import { Card } from '../ui/Card';
import { Input } from '../ui/Input';
import { useI18nStore, useT } from '../../i18n';
import type { OnboardingProfile } from '../../hooks/useOnboardingProfiles';
import { resolveProfileName } from './onboardingProfileText';

/** Searchable list of onboarding profiles with type, built-in and update/customized markers. */
export function ProfileList({ profiles, selectedId, onSelect }: {
  profiles: OnboardingProfile[] | undefined;
  selectedId: string | null;
  onSelect: (profile: OnboardingProfile) => void;
}) {
  const t = useT();
  const locale = useI18nStore(s => s.locale);
  const [search, setSearch] = useState('');

  const filtered = useMemo(() => {
    if (!profiles) return [];
    if (!search) return profiles;
    const q = search.toLowerCase();
    return profiles.filter(p =>
      p.key.includes(q) ||
      resolveProfileName(p, locale).toLowerCase().includes(q)
    );
  }, [profiles, search, locale]);

  return (
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
            onClick={() => onSelect(profile)}
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
                      ? 'bg-accent/10 text-accent'
                      : 'bg-surface-raised text-text-tertiary'
                  }`}>
                    {profile.type === 'BASE' ? t.onboardingProfiles.typeBase : t.onboardingProfiles.typeProfile}
                  </span>
                  {profile.builtIn && (
                    <span className="text-xs px-1.5 py-0.5 rounded bg-surface-raised text-text-secondary flex items-center gap-0.5">
                      <Shield className="w-3 h-3" />
                      {t.onboardingProfiles.builtIn}
                    </span>
                  )}
                  {profile.updateAvailable ? (
                    <span className="text-xs px-1.5 py-0.5 rounded bg-info-subtle text-info flex items-center gap-0.5">
                      <ArrowUpCircle className="w-3 h-3" />
                      {t.onboardingProfiles.updateAvailable}
                    </span>
                  ) : profile.customized && (
                    <span className="text-xs px-1.5 py-0.5 rounded bg-human-subtle text-human flex items-center gap-0.5">
                      <PencilLine className="w-3 h-3" />
                      {t.onboardingProfiles.customized}
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
  );
}
