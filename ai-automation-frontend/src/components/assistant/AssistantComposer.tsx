import { useEffect } from 'react';
import { Send } from 'lucide-react';
import { useT } from '../../i18n';

/** Height up to which the input grows with its content; beyond it the field scrolls. */
const MAX_INPUT_HEIGHT = 240;

function fitHeight(el: HTMLTextAreaElement) {
  el.style.height = 'auto';
  el.style.height = `${Math.min(el.scrollHeight, MAX_INPUT_HEIGHT)}px`;
}

/** Message input of the assistant panel: Enter sends, Shift+Enter breaks the line. */
export function AssistantComposer({ value, onChange, onSend, inputRef }: {
  value: string;
  onChange: (value: string) => void;
  onSend: () => void;
  /** Owned by the panel: it focuses the field and hands it to the suggestion chips. */
  inputRef: React.RefObject<HTMLTextAreaElement | null>;
}) {
  const t = useT();

  // Auto-grow the input to fit its content (typing, paste, or a programmatic prefill like the
  // Diagnose button) up to a max height, then scroll. The textarea's onInput handler only fires
  // on real keystrokes/paste, not on programmatic value changes — this effect covers those.
  useEffect(() => {
    if (inputRef.current) fitHeight(inputRef.current);
  }, [value, inputRef]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      onSend();
    }
  };

  return (
    <div
      className="px-3 py-3 shrink-0"
      style={{ borderTop: '1px solid var(--color-assistant-border)' }}
    >
      <div
        className="flex items-end gap-2 rounded-xl px-3 py-2
                   focus-within:ring-1 focus-within:ring-accent/40 transition-all"
        style={{
          backgroundColor: 'var(--color-assistant)',
          border: '1px solid var(--color-assistant-border)',
        }}
      >
        <textarea
          ref={inputRef}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={t.assistant.inputPlaceholder}
          rows={1}
          className="flex-1 resize-none bg-transparent text-sm text-text placeholder-text-tertiary
                     outline-none min-h-[24px] max-h-[240px] leading-6 py-0 overflow-y-auto"
          style={{ height: 'auto' }}
          onInput={(e) => fitHeight(e.currentTarget)}
        />
        <button
          onClick={onSend}
          disabled={!value.trim()}
          aria-label={t.assistant.send}
          title={t.assistant.send}
          className="shrink-0 w-7 h-7 rounded-lg bg-accent text-text-inverse flex items-center justify-center
                     disabled:opacity-40 hover:bg-accent-hover transition-colors"
        >
          <Send className="w-3.5 h-3.5" />
        </button>
      </div>
      <p className="text-[10px] text-text-tertiary text-center mt-1.5">
        {t.assistant.newlineHint}
      </p>
    </div>
  );
}
