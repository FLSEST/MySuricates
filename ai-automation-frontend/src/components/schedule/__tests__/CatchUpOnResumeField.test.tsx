import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { CatchUpOnResumeField } from '../CatchUpOnResumeField';
import { en } from '../../../i18n/en';

/** The hint names the actual global catch-up state instead of claiming "default: off" (ade3fef8). */
describe('CatchUpOnResumeField', () => {
  it('says the schedule is caught up anyway while catching up is on globally', () => {
    render(<CatchUpOnResumeField checked={false} onChange={() => {}} globalCatchUp />);
    expect(screen.getByTestId('catch-up-hint')).toHaveTextContent(en.schedules.catchUpOnResumeHintGlobalOn);
  });

  it('says only this schedule is caught up while catching up is off globally', () => {
    render(<CatchUpOnResumeField checked={false} onChange={() => {}} globalCatchUp={false} />);
    expect(screen.getByTestId('catch-up-hint')).toHaveTextContent(en.schedules.catchUpOnResumeHintGlobalOff);
  });

  it('claims no state while the global setting is unknown', () => {
    render(<CatchUpOnResumeField checked={false} onChange={() => {}} globalCatchUp={undefined} />);
    const hint = screen.getByTestId('catch-up-hint');
    expect(hint).toHaveTextContent(en.schedules.catchUpOnResumeHintGlobalUnknown);
    expect(hint).not.toHaveTextContent(/default: off/i);
  });

  it('reports the checkbox state', () => {
    const onChange = vi.fn();
    render(<CatchUpOnResumeField checked={false} onChange={onChange} globalCatchUp={false} />);
    fireEvent.click(screen.getByRole('checkbox'));
    expect(onChange).toHaveBeenCalledWith(true);
  });
});
