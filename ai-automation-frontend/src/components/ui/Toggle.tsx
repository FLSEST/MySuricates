interface ToggleProps {
  enabled: boolean;
  onChange: (enabled: boolean) => void;
  label?: string;
  disabled?: boolean;
}

export function Toggle({ enabled, onChange, label, disabled }: ToggleProps) {
  return (
    <label className="inline-flex items-center gap-3 cursor-pointer select-none">
      <button
        role="switch"
        aria-checked={enabled}
        disabled={disabled}
        onClick={() => onChange(!enabled)}
        className={`relative inline-flex h-6 w-11 shrink-0 rounded-full border-2 border-transparent
                    transition-colors duration-200 focus-ring
                    ${enabled ? 'bg-accent' : 'bg-border-strong'}
                    ${disabled ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer'}`}
      >
        <span
          className={`pointer-events-none inline-block h-5 w-5 rounded-full bg-white shadow-lg
                      transform transition-transform duration-200
                      ${enabled ? 'translate-x-5' : 'translate-x-0'}`}
        />
      </button>
      {label && <span className="text-sm text-text">{label}</span>}
    </label>
  );
}
