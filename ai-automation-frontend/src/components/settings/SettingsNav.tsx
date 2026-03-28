import { useState, useEffect, useRef, useCallback } from 'react';

export interface NavSection {
  id: string;
  label: string;
  visible: boolean;
}

interface SettingsNavProps {
  sections: NavSection[];
  onNavigate?: (id: string) => void;
}

export function SettingsNav({ sections, onNavigate }: SettingsNavProps) {
  const firstSection = sections[0] as NavSection | undefined;
  const [activeId, setActiveId] = useState(firstSection?.id ?? '');
  const observerRef = useRef<IntersectionObserver | null>(null);
  const visibleSections = sections.filter(s => s.visible);

  const handleObserve = useCallback((entries: IntersectionObserverEntry[]) => {
    const visible = entries.filter(e => e.isIntersecting);
    if (visible.length > 0) {
      visible.sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
      const topmost = visible[0] as IntersectionObserverEntry | undefined;
      if (topmost) setActiveId(topmost.target.id);
    }
  }, []);

  useEffect(() => {
    observerRef.current?.disconnect();
    observerRef.current = new IntersectionObserver(handleObserve, {
      rootMargin: '-10% 0px -70% 0px',
      threshold: 0,
    });

    visibleSections.forEach(s => {
      const el = document.getElementById(s.id);
      if (el) observerRef.current!.observe(el);
    });

    return () => observerRef.current?.disconnect();
  }, [visibleSections.map(s => s.id).join(','), handleObserve]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleClick = (id: string) => {
    onNavigate?.(id);
    // Wait for the collapse/expand CSS transition (200ms) to finish
    // so the browser calculates scroll position on the final layout
    setTimeout(() => {
      const el = document.getElementById(id);
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
    }, 250);
  };

  return (
    <>
      {/* Desktop: sticky sidebar */}
      <nav className="hidden md:block w-44 shrink-0">
        <div className="sticky top-6 space-y-1">
          {visibleSections.map(s => (
            <button
              key={s.id}
              onClick={() => handleClick(s.id)}
              className={`block w-full text-left px-3 py-1.5 rounded-lg text-sm transition-colors ${
                activeId === s.id
                  ? 'bg-accent/10 text-accent font-medium'
                  : 'text-text-secondary hover:text-text hover:bg-surface-hover'
              }`}
            >
              {s.label}
            </button>
          ))}
        </div>
      </nav>

      {/* Mobile: horizontal pill bar */}
      <div className="md:hidden flex gap-2 overflow-x-auto pb-2 -mx-1 px-1">
        {visibleSections.map(s => (
          <button
            key={s.id}
            onClick={() => handleClick(s.id)}
            className={`shrink-0 px-3 py-1 rounded-full text-xs font-medium transition-colors ${
              activeId === s.id
                ? 'bg-accent text-white'
                : 'bg-surface-hover text-text-secondary'
            }`}
          >
            {s.label}
          </button>
        ))}
      </div>
    </>
  );
}
