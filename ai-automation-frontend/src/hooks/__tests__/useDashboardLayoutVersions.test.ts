import { describe, it, expect } from 'vitest';
import { MAX_LAYOUT_REQUEST_BYTES, exceedsLayoutRequestLimit, type SaveDashboardLayoutRequest } from '../useDashboardLayoutVersions';

function requestWithText(text: string): SaveDashboardLayoutRequest {
  return {
    deviceLabel: 'Laptop',
    formatVersion: 2,
    baseVersionId: 'v1',
    force: false,
    layout: {
      activeViewId: 'default',
      views: [{ id: 'default', name: '', widgets: [{ instanceId: 'note', type: 'note', config: { text } }] }],
    },
  };
}

function byteLength(request: SaveDashboardLayoutRequest): number {
  return new TextEncoder().encode(JSON.stringify(request)).length;
}

describe('exceedsLayoutRequestLimit', () => {
  it('is false exactly at the limit and true one byte over it', () => {
    // 'x' is a single UTF-8 byte and needs no JSON escaping, so padding chars map 1:1 to bytes.
    const overhead = byteLength(requestWithText(''));
    const exact = requestWithText('x'.repeat(MAX_LAYOUT_REQUEST_BYTES - overhead));
    expect(byteLength(exact)).toBe(MAX_LAYOUT_REQUEST_BYTES);
    expect(exceedsLayoutRequestLimit(exact)).toBe(false);

    const over = requestWithText('x'.repeat(MAX_LAYOUT_REQUEST_BYTES - overhead + 1));
    expect(byteLength(over)).toBe(MAX_LAYOUT_REQUEST_BYTES + 1);
    expect(exceedsLayoutRequestLimit(over)).toBe(true);
  });

  it('measures UTF-8 bytes, not the UTF-16 string length, for multi-byte characters', () => {
    // BMP character: 1 UTF-16 code unit (counts once in .length) but 3 UTF-8 bytes.
    const request = requestWithText('文'.repeat(100_000));
    const json = JSON.stringify(request);

    expect(json.length).toBeLessThan(MAX_LAYOUT_REQUEST_BYTES);
    expect(exceedsLayoutRequestLimit(request)).toBe(true);
  });
});
