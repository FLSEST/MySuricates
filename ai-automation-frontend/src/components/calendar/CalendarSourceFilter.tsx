import { useState, useRef, useEffect } from 'react';
import type { CalendarSourceConfig } from '../../types';
import { COLOR_PALETTE } from '../../hooks/useCalendarColors';

interface Props {
  sources: CalendarSourceConfig[];
  onToggle: (sourceId: string) => void;
  onColorChange?: (sourceId: string, color: string) => void;
}

export function CalendarSourceFilter({ sources, onToggle, onColorChange }: Props) {
  const [pickerFor, setPickerFor] = useState<string | null>(null);
  const popoverRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!pickerFor) return;
    const handler = (e: MouseEvent) => {
      if (popoverRef.current && !popoverRef.current.contains(e.target as Node)) {
        setPickerFor(null);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [pickerFor]);

  return (
    <div className="flex flex-wrap gap-2">
      {sources.map((source) => (
        <div key={source.sourceId} className="relative">
          <button
            onClick={() => onToggle(source.sourceId)}
            className={`
              inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-medium
              transition-all border
              ${source.enabled
                ? 'border-border bg-surface text-text'
                : 'border-transparent bg-surface-secondary text-text-tertiary opacity-50'
              }
            `}
          >
            <span
              className={`w-2.5 h-2.5 rounded-full cursor-pointer ${source.enabled ? source.color : 'bg-text-tertiary'}`}
              onClick={(e) => {
                e.stopPropagation();
                if (onColorChange) setPickerFor(pickerFor === source.sourceId ? null : source.sourceId);
              }}
            />
            {source.label}
          </button>

          {pickerFor === source.sourceId && onColorChange && (
            <div
              ref={popoverRef}
              className="absolute top-full left-0 mt-1 z-50 bg-surface border border-border rounded-lg shadow-lg p-2 grid grid-cols-4 gap-1.5"
            >
              {COLOR_PALETTE.map((color) => (
                <button
                  key={color}
                  onClick={() => {
                    onColorChange(source.sourceId, color);
                    setPickerFor(null);
                  }}
                  className={`w-6 h-6 rounded-full ${color} transition-transform hover:scale-125 ${
                    source.color === color ? 'ring-2 ring-offset-1 ring-text-secondary' : ''
                  }`}
                />
              ))}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
