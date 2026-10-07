import { afterEach, describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { useI18nStore } from '../../../i18n';
import { StatusBadge } from '../StatusBadge';

afterEach(() => useI18nStore.setState({ locale: 'en' }));

describe('StatusBadge', () => {
  // 740fa214: COMPLETED printed the raw enum, COMPLETED_WITH_ERRORS a fixed "Completed" — same run list, two spellings
  it.each([
    ['COMPLETED', 'Completed', 'success'],
    ['COMPLETED_WITH_ERRORS', 'Completed with errors', 'warning'],
    ['FAILED', 'Failed', 'danger'],
    ['INTERRUPTED', 'Interrupted', 'warning'],
    ['SKIPPED', 'Skipped', 'default'],
    ['STOPPED', 'Stopped', 'default'],
  ])('labels %s as "%s" (%s)', (status, label, variant) => {
    render(<StatusBadge status={status} />);

    const badge = screen.getByText(label);
    if (variant !== 'default') expect(badge).toHaveClass(`bg-${variant}-subtle`, `text-${variant}`);
  });

  it('follows the UI language', () => {
    useI18nStore.setState({ locale: 'de' });
    render(<StatusBadge status="COMPLETED_WITH_ERRORS" />);

    expect(screen.getByText('Mit Fehlern abgeschlossen')).toHaveClass('bg-warning-subtle');
  });

  it('keeps an explicit label and shows an unknown status as it is', () => {
    render(<><StatusBadge status="COMPLETED" label="Done here" /><StatusBadge status="SOMETHING_NEW" /></>);

    expect(screen.getByText('Done here')).toBeInTheDocument();
    expect(screen.getByText('SOMETHING_NEW')).toBeInTheDocument();
  });
});
