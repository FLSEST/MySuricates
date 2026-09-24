import { useT } from '../../i18n';
import { CONTENT_LANGUAGES, type ContentLanguage } from './onboardingProfileText';

/** DE|EN switch for the language of a profile's content (editor and shipped text). */
export function ContentLanguageToggle({ value, onChange }: {
  value: ContentLanguage;
  onChange: (lang: ContentLanguage) => void;
}) {
  const t = useT();
  return (
    <div role="group" aria-label={t.onboardingProfiles.contentLanguage} className="inline-flex rounded-lg border border-border p-0.5">
      {CONTENT_LANGUAGES.map(lang => (
        <button
          key={lang}
          type="button"
          aria-pressed={value === lang}
          onClick={() => onChange(lang)}
          className={`px-2.5 py-0.5 rounded-md text-xs font-medium transition-colors ${
            value === lang ? 'bg-accent text-text-inverse' : 'text-text-secondary hover:text-text'
          }`}
        >
          {lang.toUpperCase()}
        </button>
      ))}
    </div>
  );
}
