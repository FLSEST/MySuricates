import { afterEach, describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { de } from '../../../i18n/de';
import { ProviderStatsCard } from '../ProviderStatsCard';

afterEach(() => useI18nStore.setState({ locale: 'en' }));

// 17eb7994: `toFixed(0)` wrote 1,234,567 tokens as "1235k" in both languages
describe('ProviderStatsCard', () => {
  it('shortens token counts with the separators of the app language', () => {
    useI18nStore.setState({ locale: 'de' });
    renderWithProviders(
      <ProviderStatsCard data={[{ provider: 'CLAUDE', runCount: 3, totalTokens: 1_234_567, totalCostUsd: 0, successRate: 1 }]} />,
    );
    expect(screen.getByText(`1,2M ${de.charts.tokens}`)).toBeInTheDocument();
  });
});
