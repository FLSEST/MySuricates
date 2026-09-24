import type { OnboardingProfile } from '../hooks/useOnboardingProfiles';

export function resolveProfileName(profile: OnboardingProfile, locale: string): string {
  return profile.name[locale] || profile.name['en'] || profile.key;
}

/** Languages a profile's content is written in; a missing one falls back to the other when rendered. */
export const CONTENT_LANGUAGES = ['de', 'en'] as const;
export type ContentLanguage = (typeof CONTENT_LANGUAGES)[number];
export type ContentByLanguage = Record<ContentLanguage, string>;

export function contentOf(profile: OnboardingProfile): ContentByLanguage {
  return { de: profile.content?.de ?? '', en: profile.content?.en ?? '' };
}
