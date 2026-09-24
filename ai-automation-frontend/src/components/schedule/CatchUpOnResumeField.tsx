import { useT } from '../../i18n';

interface CatchUpOnResumeFieldProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  /**
   * The global setting "catch up missed runs" (on by default). Unknown while it loads — then the
   * hint names the setting instead of claiming a state.
   */
  globalCatchUp: boolean | undefined;
}

/**
 * The per-schedule catch-up option. Its hint names the actual global state (ade3fef8): with
 * catching up on globally the option changes nothing, with it off only this schedule is caught up.
 */
export function CatchUpOnResumeField({ checked, onChange, globalCatchUp }: CatchUpOnResumeFieldProps) {
  const t = useT();
  const hint = globalCatchUp === undefined
    ? t.schedules.catchUpOnResumeHintGlobalUnknown
    : globalCatchUp
      ? t.schedules.catchUpOnResumeHintGlobalOn
      : t.schedules.catchUpOnResumeHintGlobalOff;

  return (
    <label className="flex items-start gap-2 cursor-pointer">
      <input
        type="checkbox"
        className="mt-0.5"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
      />
      <span className="text-sm text-text">
        {t.schedules.catchUpOnResume}
        <span className="block text-xs text-text-tertiary" data-testid="catch-up-hint">{hint}</span>
      </span>
    </label>
  );
}
