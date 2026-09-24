import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { SkipUntilBanner } from '../SkipUntilBanner';
import { useI18nStore } from '../../../i18n';
import { formatDateTime } from '../../../utils/formatTime';

beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
});

/** The skip-until pause from the configuration is visible on the schedules page (a776fd2a). */
describe('SkipUntilBanner', () => {
  it('renders nothing without an active pause', () => {
    const { container } = render(<SkipUntilBanner pausedUntil={null} />);
    expect(container).toBeEmptyDOMElement();
    const { container: undef } = render(<SkipUntilBanner pausedUntil={undefined} />);
    expect(undef).toBeEmptyDOMElement();
  });

  it('names the end of the pause and what happens to occurrences before it', () => {
    render(<SkipUntilBanner pausedUntil="2026-10-01T22:00:00Z" />);
    const banner = screen.getByTestId('skip-until-banner');
    expect(banner).toHaveTextContent(`Schedules paused until ${formatDateTime('2026-10-01T22:00:00Z', 'en')}`);
    expect(banner).toHaveTextContent('skipped, not caught up');
  });
});
