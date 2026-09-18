import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { Check } from 'lucide-react';

export interface DashboardMenuItem {
  id: string;
  label: string;
  /** Set for choices with a current value (e.g. the widget size); leave out for plain actions. */
  checked?: boolean;
}

interface DashboardMenuProps {
  /** Accessible name of the trigger and the menu. */
  label: string;
  icon: ReactNode;
  items: DashboardMenuItem[];
  disabled?: boolean;
  onSelect: (id: string) => void;
  /** `toolbar`: small icon in a widget's edit toolbar; `button`: like the view action buttons. */
  variant?: 'toolbar' | 'button';
}

const TRIGGER_CLASSES = {
  toolbar: 'rounded-md p-1 text-text-secondary hover:bg-surface-hover hover:text-text disabled:cursor-not-allowed disabled:opacity-50',
  button: 'btn-base px-3 py-1.5 text-xs text-text-secondary hover:bg-surface-raised hover:text-text disabled:cursor-not-allowed disabled:opacity-50',
};

/**
 * A button with a small drop-down menu (ARIA menu button): opens on click, focuses the checked or
 * first item, arrow keys/Home/End move, Escape closes and returns focus, a click outside or Tab
 * closes. Items are plain text.
 */
export function DashboardMenu({ label, icon, items, disabled = false, onSelect, variant = 'toolbar' }: DashboardMenuProps) {
  const [open, setOpen] = useState(false);
  const menuId = useId();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const shown = open && !disabled;
  const initialIndex = Math.max(items.findIndex((item) => item.checked), 0);

  useEffect(() => {
    if (!shown) return;
    menuRef.current?.querySelectorAll<HTMLButtonElement>('[role^="menuitem"]')[initialIndex]?.focus();
    const closeOnOutsideClick = (event: MouseEvent) => {
      const target = event.target as Node;
      if (!menuRef.current?.contains(target) && !triggerRef.current?.contains(target)) setOpen(false);
    };
    document.addEventListener('mousedown', closeOnOutsideClick);
    return () => document.removeEventListener('mousedown', closeOnOutsideClick);
  }, [shown, initialIndex]);

  const close = () => {
    setOpen(false);
    triggerRef.current?.focus();
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      close();
      return;
    }
    if (event.key === 'Tab') {
      setOpen(false);
      return;
    }
    const buttons = Array.from(menuRef.current?.querySelectorAll<HTMLButtonElement>('[role^="menuitem"]') ?? []);
    const current = buttons.findIndex((button) => button === document.activeElement);
    const targets: Record<string, number> = { ArrowDown: current + 1, ArrowUp: current - 1, Home: 0, End: buttons.length - 1 };
    const target = targets[event.key];
    if (target === undefined) return;
    event.preventDefault();
    buttons[(target + buttons.length) % buttons.length]?.focus();
  };

  // Radio items (a value to choose, e.g. widget size) form one group; plain action items don't.
  const isRadioGroup = items.length > 0 && items.every((item) => item.checked !== undefined);
  const renderItem = (item: DashboardMenuItem) => (
    <button
      key={item.id}
      type="button"
      role={item.checked === undefined ? 'menuitem' : 'menuitemradio'}
      aria-checked={item.checked}
      tabIndex={-1}
      onClick={() => {
        close();
        onSelect(item.id);
      }}
      className="flex w-full items-center gap-2 rounded-md px-2.5 py-1.5 text-left text-xs text-text-secondary hover:bg-surface-hover hover:text-text focus:bg-surface-hover focus:text-text focus:outline-none"
    >
      {item.checked !== undefined && (
        <span className="flex w-3.5 shrink-0 justify-center">{item.checked && <Check className="h-3.5 w-3.5 text-accent" />}</span>
      )}
      <span className="min-w-0 truncate">{item.label}</span>
    </button>
  );

  return (
    <div className="relative">
      <button
        ref={triggerRef}
        type="button"
        aria-haspopup="menu"
        aria-expanded={shown}
        aria-controls={shown ? menuId : undefined}
        aria-label={label}
        title={label}
        disabled={disabled}
        onClick={() => setOpen((value) => !value)}
        className={TRIGGER_CLASSES[variant]}
      >
        {icon}
      </button>
      {shown && (
        <div
          ref={menuRef}
          id={menuId}
          role="menu"
          aria-label={label}
          onKeyDown={handleKeyDown}
          className="absolute right-0 top-full z-30 mt-1 min-w-44 rounded-lg border border-border bg-surface-overlay p-1 shadow-lg"
        >
          {isRadioGroup ? (
            <div role="group" aria-label={label}>
              {items.map((item) => renderItem(item))}
            </div>
          ) : (
            items.map((item) => renderItem(item))
          )}
        </div>
      )}
    </div>
  );
}
