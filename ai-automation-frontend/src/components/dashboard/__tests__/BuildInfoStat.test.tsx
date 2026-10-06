import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterEach, afterAll } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { BuildInfoStat } from '../StatWidgets';

// __APP_VERSION__ is 1.0.0-SNAPSHOT in vitest.config.ts — the bare number is displayed.
const FE_VERSION = '1.0.0';

function systemInfo(version: string, buildType = 'RELEASE') {
  return http.get('/api/v1/system/info', () => HttpResponse.json({
    version, buildType, buildTimestamp: '2026-01-01T00:00:00.000Z',
  }));
}

describe('BuildInfoStat', () => {
  beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
  afterEach(() => server.resetHandlers());
  afterAll(() => server.close());

  it('shows the backend and the frontend version side by side', async () => {
    useI18nStore.setState({ locale: 'en' });
    server.use(systemInfo(FE_VERSION));
    renderWithProviders(<BuildInfoStat />);

    expect(await screen.findByText(`v${FE_VERSION}`)).toBeInTheDocument();
    expect(screen.getByText(`BE ${FE_VERSION}`)).toBeInTheDocument();
    expect(screen.getByText(`FE ${FE_VERSION}`)).toBeInTheDocument();
    expect(screen.getByText('RELEASE')).toBeInTheDocument();
    // Gleiche Nummern: keine Warnung.
    expect(screen.queryByLabelText(/different builds/i)).not.toBeInTheDocument();
  });

  it('warns when the backend runs another version than the frontend bundle', async () => {
    useI18nStore.setState({ locale: 'en' });
    server.use(systemInfo('0.9.0', 'SNAPSHOT'));
    renderWithProviders(<BuildInfoStat />);

    expect(await screen.findByText('BE 0.9.0')).toBeInTheDocument();
    expect(screen.getByText(`FE ${FE_VERSION}`)).toBeInTheDocument();
    expect(screen.getByLabelText(/different builds/i)).toBeInTheDocument();
  });

  it('compares without the -SNAPSHOT suffix the development versions carry', async () => {
    useI18nStore.setState({ locale: 'en' });
    server.use(systemInfo('1.0.0', 'SNAPSHOT'));
    renderWithProviders(<BuildInfoStat />);

    expect(await screen.findByText('FE 1.0.0')).toBeInTheDocument();
    expect(screen.queryByLabelText(/different builds/i)).not.toBeInTheDocument();
  });

  it('stays quiet while the backend answer is still missing', async () => {
    useI18nStore.setState({ locale: 'en' });
    server.use(http.get('/api/v1/system/info', () => HttpResponse.error()));
    renderWithProviders(<BuildInfoStat />);

    expect(await screen.findByText('BE ?')).toBeInTheDocument();
    expect(screen.queryByLabelText(/different builds/i)).not.toBeInTheDocument();
  });
});
