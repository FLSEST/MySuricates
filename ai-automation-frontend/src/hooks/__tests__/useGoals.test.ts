import { describe, it, expect } from 'vitest';
import { goalRelationsRetry } from '../useGoals';

describe('goalRelationsRetry', () => {
  it('never retries a 404 – an older backend without the relations endpoint stays that way until restart', () => {
    expect(goalRelationsRetry(0, { status: 404, error: 'Not Found' })).toBe(false);
  });

  it('retries other errors like the app default (once), but never a 429', () => {
    expect(goalRelationsRetry(0, { status: 500, error: 'boom' })).toBe(true);
    expect(goalRelationsRetry(1, { status: 500, error: 'boom' })).toBe(false);
    expect(goalRelationsRetry(0, { status: 429, error: 'slow down' })).toBe(false);
  });
});
