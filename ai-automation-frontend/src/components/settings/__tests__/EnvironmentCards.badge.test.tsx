import { describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { en } from '../../../i18n/en';
import type { StagingStatus, TestingStatus } from '../../../types';
import { StagingCard } from '../StagingCard';
import { TestingCard } from '../TestingCard';

const noop = vi.fn();
const syncNoop = vi.fn().mockResolvedValue({});

function staging(containerRunning: boolean, healthy: boolean): StagingStatus {
  return {
    containerRunning, healthy, promotePhase: 'IDLE', promoteStep: 0, promoteTotalSteps: 5,
    lastSnapshot: null, error: null, stableVersion: null, stableBuildTimestamp: null,
  };
}

function testing(containerRunning: boolean, healthy: boolean): TestingStatus {
  return { containerRunning, healthy, testVersion: null, testBuildTimestamp: null };
}

function renderStaging(status: StagingStatus) {
  renderWithProviders(
    <StagingCard staging={status} devVersion={undefined} onStart={noop} onStop={noop} onPromote={noop}
                 onCancel={noop} onSyncAll={syncNoop} onSyncSelective={syncNoop} syncResult={undefined}
                 startLoading={false} stopLoading={false} promoteLoading={false} syncLoading={false} />,
  );
  return within(screen.getByTestId('staging-card'));
}

function renderTesting(status: TestingStatus) {
  renderWithProviders(
    <TestingCard testing={status} devVersion={undefined} onStart={noop} onStop={noop} onReset={syncNoop}
                 onSyncAll={syncNoop} onSyncSelective={syncNoop} onPullChanges={syncNoop} onRepairAll={noop}
                 syncResult={undefined} pullResult={undefined} startLoading={false} stopLoading={false}
                 resetLoading={false} syncLoading={false} pullLoading={false} repairAllLoading={false} />,
  );
  return within(screen.getByTestId('testing-card'));
}

// The badge colour already said "starting" for a running but not yet healthy container; the text said "Running".
describe('environment status badges', { timeout: 15_000 }, () => {
  it.each([
    ['stopped', false, false, en.settings.stagingStopped],
    ['running but not healthy', true, false, en.settings.stagingStarting],
    ['running and healthy', true, true, en.settings.stagingRunning],
  ])('stable card: %s', (_, running, healthy, label) => {
    expect(renderStaging(staging(running, healthy)).getByText(label)).toBeInTheDocument();
  });

  it.each([
    ['stopped', false, false, en.settings.testingStopped],
    ['running but not healthy', true, false, en.settings.testingStarting],
    ['running and healthy', true, true, en.settings.testingRunning],
  ])('test card: %s', (_, running, healthy, label) => {
    expect(renderTesting(testing(running, healthy)).getByText(label)).toBeInTheDocument();
  });

  it('does not call a starting container running', () => {
    expect(renderStaging(staging(true, false)).queryByText(en.settings.stagingRunning)).not.toBeInTheDocument();
  });
});
