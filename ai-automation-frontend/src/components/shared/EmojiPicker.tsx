import { useId } from 'react';
import { X } from 'lucide-react';
import { useT } from '../../i18n';

/**
 * A curated set, grouped loosely by theme (writing, code, data, research, communication,
 * safety, business). Contains every icon the built-in skills ship with, so they show as chosen.
 */
const EMOJI_OPTIONS = [
  '📝', '✏️', '📄', '🗒️', '📋', '📚',
  '🛠️', '🧪', '🐛', '💻', '⚙️', '🧩',
  '📊', '📈', '🗃️', '🧮',
  '🔍', '🔬', '💡', '🧭', '🌍',
  '📧', '💬', '📱', '📣',
  '🛡️', '✅', '⚠️', '🔒',
  '🏛️', '💼', '🎯', '🚀', '⚡',
];

/**
 * Pick an icon from a small emoji grid, clear it, or type your own. `null` means "no icon" —
 * the caller then shows its fallback (e.g. the category icon).
 */
export function EmojiPicker({
  label,
  value,
  onChange,
  options = EMOJI_OPTIONS,
}: {
  label: string;
  value: string | null;
  onChange: (value: string | null) => void;
  options?: string[];
}) {
  const t = useT();
  const customId = useId();
  const custom = value && !options.includes(value) ? value : '';
  const chip = 'w-9 h-9 inline-flex items-center justify-center rounded-lg text-lg leading-none transition-colors';
  const chosen = 'bg-accent/10 ring-2 ring-accent';
  const idle = 'hover:bg-surface-raised';

  return (
    <div className="space-y-1.5" data-testid="emoji-picker">
      <span className="block text-sm font-medium text-text-secondary">{label}</span>
      <div role="group" aria-label={label} className="flex flex-wrap items-center gap-1">
        <button
          type="button"
          onClick={() => onChange(null)}
          aria-pressed={!value}
          aria-label={t.common.none}
          title={t.common.none}
          className={`${chip} text-text-tertiary ${!value ? chosen : idle}`}
        >
          <X className="w-4 h-4" />
        </button>
        {options.map((emoji) => (
          <button
            key={emoji}
            type="button"
            onClick={() => onChange(emoji)}
            aria-pressed={value === emoji}
            className={`${chip} ${value === emoji ? chosen : idle}`}
          >
            {emoji}
          </button>
        ))}
        <label htmlFor={customId} className="sr-only">{t.common.customIcon}</label>
        <input
          id={customId}
          value={custom}
          maxLength={16}
          placeholder={t.common.customIcon}
          onChange={(e) => onChange(e.target.value.trim() === '' ? null : e.target.value)}
          className={`input-base w-24! py-1.5! text-center ${custom ? 'ring-2 ring-accent' : ''}`}
        />
      </div>
    </div>
  );
}
