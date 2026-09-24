import { useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useSettingsStore } from '../stores/settingsStore';

/**
 * Visible hand-off from Simple to Pro: switches the mode and, with a target, navigates there.
 * Without a target the current page simply re-renders in Pro.
 */
export function useOpenInPro(): (to?: string) => void {
  const navigate = useNavigate();
  const setSimpleMode = useSettingsStore((s) => s.setSimpleMode);
  return useCallback((to?: string) => {
    setSimpleMode(false);
    if (to) navigate(to);
  }, [navigate, setSimpleMode]);
}
