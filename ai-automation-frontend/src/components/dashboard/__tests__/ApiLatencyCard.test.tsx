import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { ApiLatencyCard } from '../ApiLatencyCard';
import type { RequestMetricRoute } from '../../../types';

function route(overrides: Partial<RequestMetricRoute> & { route: string }): RequestMetricRoute {
  return {
    calls: 100,
    p50Ms: 10,
    p95Ms: 20,
    maxMs: 30,
    p95WaitMs: 2,
    waitShare: 0.1,
    maxSqlStatements: 3,
    avgSqlStatements: 3,
    maxResponseBytes: 1000,
    avgResponseBytes: 1000,
    ...overrides,
  };
}

describe('ApiLatencyCard', () => {
  it('shows an empty state when nothing has been measured', () => {
    renderWithProviders(<ApiLatencyCard data={[]} />);

    expect(screen.getByText(/noch nichts gemessen|No requests measured/i)).toBeInTheDocument();
  });

  it('lists the slowest routes first', () => {
    renderWithProviders(
      <ApiLatencyCard
        data={[
          route({ route: 'GET /api/v1/todos', p95Ms: 120 }),
          route({ route: 'GET /api/v1/workflows', p95Ms: 900 }),
          route({ route: 'GET /api/v1/projects', p95Ms: 15 }),
        ]}
      />
    );

    const listed = screen.getAllByTitle(/^GET /).map((el) => el.textContent);
    expect(listed).toEqual([
      'GET /api/v1/workflows',
      'GET /api/v1/todos',
      'GET /api/v1/projects',
    ]);
  });

  it('keeps only the top N routes', () => {
    const many = Array.from({ length: 9 }, (_, i) =>
      route({ route: `GET /api/v1/r${i}`, p95Ms: i * 10 })
    );

    renderWithProviders(<ApiLatencyCard data={many} topN={3} />);

    expect(screen.getAllByTitle(/^GET /)).toHaveLength(3);
  });

  it('shows how many calls and statements a route took', () => {
    renderWithProviders(
      <ApiLatencyCard data={[route({ route: 'GET /api/v1/todos', calls: 42, maxSqlStatements: 47 })]} />
    );

    expect(screen.getByText(/42/)).toBeInTheDocument();
    expect(screen.getByText(/47/)).toBeInTheDocument();
  });

  /**
   * The point of the tile: when most of the time is queuing for the single connection, the
   * bottleneck is whatever holds it — not the routes listed underneath.
   */
  it('warns when most of the time is spent waiting for the connection', () => {
    renderWithProviders(
      <ApiLatencyCard data={[route({ route: 'GET /api/v1/todos', waitShare: 0.9, calls: 10 })]} />
    );

    expect(screen.getByText('90%')).toBeInTheDocument();
    expect(screen.getByText(/überwiegend gewartet|Most time is spent queuing/i)).toBeInTheDocument();
  });

  it('stays calm when the time is spent working', () => {
    renderWithProviders(
      <ApiLatencyCard data={[route({ route: 'GET /api/v1/todos', waitShare: 0.05, calls: 10 })]} />
    );

    expect(screen.getByText('5%')).toBeInTheDocument();
    expect(screen.queryByText(/überwiegend gewartet|Most time is spent queuing/i)).not.toBeInTheDocument();
  });

  /** Routes with many calls must count more than a single outlier request. */
  it('weights the wait share by how often a route was called', () => {
    renderWithProviders(
      <ApiLatencyCard
        data={[
          route({ route: 'GET /api/v1/busy', waitShare: 0.1, calls: 900 }),
          route({ route: 'GET /api/v1/rare', waitShare: 1.0, calls: 100 }),
        ]}
      />
    );

    // (0.1*900 + 1.0*100) / 1000 = 0.19 — an unweighted mean would have claimed 55%.
    expect(screen.getByText('19%')).toBeInTheDocument();
  });
});
