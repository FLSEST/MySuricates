export interface FilterChipOption<T extends string> {
  value: T;
  label: string;
  /** Shown next to the label, e.g. how many entries the chip stands for. */
  count?: number;
}

interface FilterChipsProps<T extends string> {
  options: FilterChipOption<T>[];
  selected: readonly T[];
  /** The new selection, in the order of `options`. */
  onChange: (selected: T[]) => void;
  ariaLabel: string;
}

/**
 * Multi-select filter as a row of chips: each chip switches its value on or off and leaves the
 * others as they are. An empty selection is allowed — what it means is up to the caller.
 */
export function FilterChips<T extends string>({ options, selected, onChange, ariaLabel }: FilterChipsProps<T>) {
  const toggle = (value: T) => {
    const next = selected.includes(value) ? selected.filter((item) => item !== value) : [...selected, value];
    onChange(options.map((option) => option.value).filter((item) => next.includes(item)));
  };
  return (
    <div className="flex flex-wrap items-center gap-1 text-xs" role="group" aria-label={ariaLabel}>
      {options.map((option) => {
        const pressed = selected.includes(option.value);
        return (
          <button
            key={option.value}
            type="button"
            aria-pressed={pressed}
            onClick={() => toggle(option.value)}
            className={`inline-flex items-center gap-1 px-2 py-1 rounded-md whitespace-nowrap transition-colors focus-ring ${
              pressed
                ? 'bg-surface-raised text-text border border-border'
                : 'text-text-tertiary hover:text-text border border-transparent'
            }`}
          >
            {option.label}
            {option.count != null && <span className="text-text-tertiary tabular-nums">{option.count}</span>}
          </button>
        );
      })}
    </div>
  );
}
