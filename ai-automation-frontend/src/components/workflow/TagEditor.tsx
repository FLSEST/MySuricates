import { useState, type KeyboardEvent } from 'react';
import { X } from 'lucide-react';
import type { TranslationKeys } from '../../i18n';

const CATEGORIES = [
  'Productivity',
  'Dev',
  'Content',
  'Analysis',
  'Ops',
  'Data',
  'Integration',
  'Other',
] as const;

interface TagEditorProps {
  t: TranslationKeys;
  tags: string[];
  category: string | null | undefined;
  userEdited: boolean;
  onSave: (next: { tags: string[]; category: string | null }) => void;
  disabled?: boolean;
}

export function TagEditor({ t, tags, category, userEdited, onSave, disabled = false }: TagEditorProps) {
  const [draft, setDraft] = useState('');

  const addTag = (raw: string) => {
    const clean = raw.trim().toLowerCase().replace(/[^a-z0-9-]/g, '');
    if (!clean) return;
    if (tags.includes(clean)) return;
    if (tags.length >= 8) return;
    onSave({ tags: [...tags, clean], category: category ?? null });
  };

  const handleKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      if (draft.trim()) {
        addTag(draft);
        setDraft('');
      }
    }
  };

  const removeTag = (tag: string) => {
    onSave({ tags: tags.filter((x) => x !== tag), category: category ?? null });
  };

  const changeCategory = (next: string) => {
    onSave({ tags, category: next || null });
  };

  const categoryLabel = (key: string | null | undefined): string => {
    if (!key) return t.workflows.tagsUncategorized;
    const map = t.workflows.tagsCategories as Record<string, string>;
    return map[key] ?? key;
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-text">{t.workflows.tagsHeading}</h3>
        <span
          className={`text-[10px] px-1.5 py-0.5 rounded-sm ${
            userEdited ? 'bg-accent/10 text-accent' : 'bg-surface-raised text-text-tertiary'
          }`}
          title={userEdited ? t.workflows.tagsUserEdited : t.workflows.tagsGenerated}
        >
          {userEdited ? t.workflows.tagsUserEdited : t.workflows.tagsGenerated}
        </span>
      </div>

      <div>
        <label className="text-xs text-text-tertiary mb-1 block">{t.workflows.tagsCategory}</label>
        <select
          value={category ?? ''}
          onChange={(e) => changeCategory(e.target.value)}
          disabled={disabled}
          className="w-full text-xs bg-surface border border-border rounded-md px-2 py-1.5 text-text focus:outline-none focus:ring-1 focus:ring-accent disabled:opacity-50"
        >
          <option value="">{t.workflows.tagsUncategorized}</option>
          {CATEGORIES.map((c) => (
            <option key={c} value={c}>
              {categoryLabel(c)}
            </option>
          ))}
        </select>
      </div>

      <div className="flex flex-wrap gap-1.5">
        {tags.map((tag) => (
          <span
            key={tag}
            className="inline-flex items-center gap-1 px-2 py-0.5 rounded-sm text-xs font-medium bg-accent/10 text-accent"
          >
            {tag}
            <button
              onClick={() => removeTag(tag)}
              disabled={disabled}
              className="hover:text-danger transition-colors disabled:opacity-50"
              aria-label={t.common.removeItem.replace('{name}', tag)}
            >
              <X className="w-3 h-3" />
            </button>
          </span>
        ))}
      </div>

      <input
        type="text"
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={handleKey}
        disabled={disabled || tags.length >= 8}
        placeholder={t.workflows.tagsPlaceholder}
        className="w-full text-xs bg-surface border border-border rounded-md px-2 py-1.5 text-text placeholder:text-text-tertiary focus:outline-none focus:ring-1 focus:ring-accent disabled:opacity-50"
      />
    </div>
  );
}
