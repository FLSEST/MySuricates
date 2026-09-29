import { useState, useCallback } from 'react';

const STORAGE_KEY = 'calendar-source-colors';

/** Chart tokens: distinguishable and visible on every surface in every theme (themes.test). */
export const COLOR_PALETTE = [
  'bg-chart-1',
  'bg-chart-2',
  'bg-chart-3',
  'bg-chart-4',
  'bg-chart-5',
  'bg-chart-6',
  'bg-chart-7',
  'bg-chart-8',
] as const;

/**
 * Colour family of a choice stored before the palette moved to theme tokens (19ebb8a5), mapped to the
 * chart token of the same default hue. The old class is no longer generated, so it would paint nothing.
 */
const LEGACY_FAMILIES: Record<string, string> = {
  blue: 'bg-chart-1',
  sky: 'bg-chart-7',
  teal: 'bg-chart-6',
  emerald: 'bg-chart-2',
  green: 'bg-chart-2',
  amber: 'bg-chart-8',
  orange: 'bg-chart-8',
  red: 'bg-chart-4',
  pink: 'bg-chart-5',
  violet: 'bg-chart-3',
};

function migrateColor(color: string): string {
  const legacy = /^bg-([a-z]+)-500$/.exec(color);
  return (legacy && LEGACY_FAMILIES[legacy[1]!]) ?? color;
}

function loadColors(): Record<string, string> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    const stored = JSON.parse(raw) as Record<string, string>;
    return Object.fromEntries(Object.entries(stored).map(([sourceId, color]) => [sourceId, migrateColor(color)]));
  } catch {
    return {};
  }
}

export function useCalendarColors() {
  const [colors, setColors] = useState<Record<string, string>>(loadColors);

  const setColor = useCallback((sourceId: string, color: string) => {
    setColors((prev) => {
      const next = { ...prev, [sourceId]: color };
      localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
      return next;
    });
  }, []);

  const getColor = useCallback((sourceId: string, defaultColor: string) => {
    return colors[sourceId] ?? defaultColor;
  }, [colors]);

  return { colors, setColor, getColor };
}
