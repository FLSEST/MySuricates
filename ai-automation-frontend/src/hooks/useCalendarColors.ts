import { useState, useCallback } from 'react';

const STORAGE_KEY = 'calendar-source-colors';

export const COLOR_PALETTE = [
  'bg-blue-500',
  'bg-sky-500',
  'bg-teal-500',
  'bg-emerald-500',
  'bg-green-500',
  'bg-amber-500',
  'bg-orange-500',
  'bg-red-500',
  'bg-pink-500',
  'bg-violet-500',
] as const;

function loadColors(): Record<string, string> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : {};
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
