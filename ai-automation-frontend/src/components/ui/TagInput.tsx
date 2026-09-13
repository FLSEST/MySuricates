import { useState, useRef, useEffect, useCallback } from 'react';
import { X } from 'lucide-react';

interface TagInputProps {
  tags: string[];
  onChange: (tags: string[]) => void;
  suggestions?: string[];
  label?: string;
  hint?: string;
  placeholder?: string;
  compact?: boolean;
}

export function TagInput({ tags, onChange, suggestions = [], label, hint, placeholder = 'Tag hinzufügen...', compact }: TagInputProps) {
  const [input, setInput] = useState('');
  const [showSuggestions, setShowSuggestions] = useState(false);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const filtered = input.trim()
    ? suggestions.filter((s) => s.toLowerCase().includes(input.toLowerCase()) && !tags.includes(s))
    : suggestions.filter((s) => !tags.includes(s));

  const addTag = useCallback((tag: string) => {
    const trimmed = tag.trim().toLowerCase();
    if (trimmed && !tags.includes(trimmed)) {
      onChange([...tags, trimmed]);
    }
    setInput('');
    setShowSuggestions(false);
    setSelectedIndex(0);
    inputRef.current?.focus();
  }, [tags, onChange]);

  const removeTag = useCallback((tag: string) => {
    onChange(tags.filter((t) => t !== tag));
  }, [tags, onChange]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      const suggestion = filtered[selectedIndex];
      if (showSuggestions && suggestion) {
        addTag(suggestion);
      } else if (input.trim()) {
        addTag(input);
      }
    } else if (e.key === 'Backspace' && !input && tags.length > 0) {
      removeTag(tags[tags.length - 1]!);
    } else if (e.key === 'ArrowDown' && showSuggestions) {
      e.preventDefault();
      setSelectedIndex((i) => Math.min(i + 1, filtered.length - 1));
    } else if (e.key === 'ArrowUp' && showSuggestions) {
      e.preventDefault();
      setSelectedIndex((i) => Math.max(i - 1, 0));
    } else if (e.key === 'Escape') {
      setShowSuggestions(false);
    }
  };

  // Close suggestions on outside click
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setShowSuggestions(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  return (
    <div className="space-y-1.5" ref={containerRef}>
      {label && <label className="block text-sm font-medium text-text-secondary">{label}</label>}
      <div
        className={`flex flex-wrap items-center gap-1.5 input-base cursor-text ${compact ? 'py-1 px-2' : ''}`}
        onClick={() => inputRef.current?.focus()}
      >
        {tags.map((tag) => (
          <span
            key={tag}
            className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-accent/10 text-accent border border-accent/20"
          >
            {tag}
            <button
              type="button"
              onClick={(e) => { e.stopPropagation(); removeTag(tag); }}
              className="hover:text-danger transition-colors"
            >
              <X className="w-3 h-3" />
            </button>
          </span>
        ))}
        <div className="relative flex-1 min-w-[80px]">
          <input
            ref={inputRef}
            type="text"
            className={`w-full bg-transparent outline-hidden text-text placeholder:text-text-tertiary ${compact ? 'text-xs' : 'text-sm'}`}
            value={input}
            onChange={(e) => { setInput(e.target.value); setShowSuggestions(true); setSelectedIndex(0); }}
            onFocus={() => setShowSuggestions(true)}
            onKeyDown={handleKeyDown}
            placeholder={tags.length === 0 ? placeholder : ''}
          />
          {showSuggestions && filtered.length > 0 && (
            <div className="absolute left-0 top-full mt-1 w-48 bg-surface-overlay border border-border rounded-lg shadow-lg z-50 py-1 max-h-40 overflow-y-auto">
              {filtered.map((suggestion, i) => (
                <button
                  key={suggestion}
                  type="button"
                  className={`w-full text-left px-3 py-1.5 text-xs transition-colors ${
                    i === selectedIndex
                      ? 'bg-accent/10 text-accent'
                      : 'text-text-secondary hover:bg-surface-raised'
                  }`}
                  onMouseDown={(e) => { e.preventDefault(); addTag(suggestion); }}
                  onMouseEnter={() => setSelectedIndex(i)}
                >
                  {suggestion}
                </button>
              ))}
            </div>
          )}
        </div>
      </div>
      {hint && <p className="text-xs text-text-tertiary">{hint}</p>}
    </div>
  );
}
