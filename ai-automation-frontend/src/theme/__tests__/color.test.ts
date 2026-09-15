import { describe, it, expect } from 'vitest';
import {
  contrast,
  ensureContrast,
  fitContrast,
  hueDistance,
  mix,
  oklabDistance,
  toOklch,
  withHue,
  withLightness,
} from '../color';

describe('color math', () => {
  it('computes WCAG contrast ratios', () => {
    expect(contrast('#000000', '#ffffff')).toBeCloseTo(21, 5);
    expect(contrast('#ffffff', '#ffffff')).toBeCloseTo(1, 5);
    expect(contrast('#767676', '#ffffff')).toBeCloseTo(4.54, 2);
  });

  it('mixes two colors by weight of the first', () => {
    expect(mix('#000000', '#ffffff', 0.5)).toBe('#808080');
    expect(mix('#ff0000', '#0000ff', 1)).toBe('#ff0000');
    expect(mix('#ff0000', '#0000ff', 0)).toBe('#0000ff');
  });

  it('changes HLS lightness while keeping hue', () => {
    expect(withLightness('#ff0000', 0.25)).toBe('#800000');
    expect(hueDistance(withLightness('#3b82f6', 0.8), '#3b82f6')).toBeLessThan(3);
  });

  it('round-trips through OKLCH and rotates hue', () => {
    const { l, c, h } = toOklch('#ff0000');
    expect(l).toBeCloseTo(0.628, 2);
    expect(c).toBeCloseTo(0.258, 2);
    expect(h).toBeCloseTo(29.2, 0);
    expect(oklabDistance(withHue('#3b82f6', toOklch('#3b82f6').h), '#3b82f6')).toBeLessThan(0.5);
    expect(hueDistance(withHue('#859900', 145), '#22c55e')).toBeLessThan(6);
  });

  it('measures hue distance across 0°', () => {
    expect(hueDistance('#ff0040', '#ff4000')).toBeGreaterThan(0);
    expect(hueDistance('#ff0040', '#ff4000')).toBeLessThan(40);
  });

  it('shifts lightness only as far as needed to reach a contrast', () => {
    const fitted = fitContrast('#a3be8c', ['#eceff4'], 3, false);
    expect(contrast(fitted, '#eceff4')).toBeGreaterThanOrEqual(3);
    expect(contrast(fitted, '#eceff4')).toBeLessThan(3.2);
    expect(fitContrast('#000000', ['#ffffff'], 4.5, false)).toBe('#000000');
  });

  it('mixes toward a target until the contrast is met', () => {
    const c = ensureContrast('#6272a4', ['#282a36'], 3.8, '#f8f8f2');
    expect(contrast(c, '#282a36')).toBeGreaterThanOrEqual(3.8);
    expect(ensureContrast('#f8f8f2', ['#282a36'], 4.5, '#ffffff')).toBe('#f8f8f2');
  });
});
