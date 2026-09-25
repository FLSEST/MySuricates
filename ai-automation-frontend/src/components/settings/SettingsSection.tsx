import { type ReactNode } from 'react';
import { ChevronRight } from 'lucide-react';

interface SettingsSectionProps {
  id: string;
  title: string;
  children: ReactNode;
  collapsible?: boolean;
  open?: boolean;
  onToggle?: () => void;
}

export function SettingsSection({ id, title, children, collapsible = false, open = true, onToggle }: SettingsSectionProps) {
  return (
    <section id={id} className="scroll-mt-6">
      {collapsible ? (
        <button
          onClick={onToggle}
          aria-expanded={open}
          className="flex items-center gap-2 w-full text-left group mb-4"
        >
          <ChevronRight className={`w-4 h-4 text-text-secondary transition-transform duration-200 ${open ? 'rotate-90' : ''}`} />
          <h2 className="text-lg font-semibold text-text group-hover:text-accent transition-colors">{title}</h2>
        </button>
      ) : (
        <h2 className="text-lg font-semibold text-text mb-4">{title}</h2>
      )}
      <div className={`grid transition-all duration-200 ease-in-out ${open ? 'grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0'}`}>
        <div className="overflow-hidden">
          <div className="space-y-6">
            {children}
          </div>
        </div>
      </div>
    </section>
  );
}
