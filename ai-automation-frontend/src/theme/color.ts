/**
 * Farbmathematik für die Theme-Ableitung: WCAG-Kontrast, Mischen, HLS-Helligkeit
 * (wie Pythons colorsys) und OKLCH für wahrnehmungsnahe Farbtöne/Abstände.
 * Alle Farben sind 6-stellige Hex-Strings.
 */

type Rgb = [number, number, number]; // 0..1

function hexToRgb(hex: string): Rgb {
  const h = hex.replace('#', '');
  return [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16) / 255) as Rgb;
}

function rgbToHex([r, g, b]: Rgb): string {
  const byte = (v: number) => Math.round(Math.min(1, Math.max(0, v)) * 255).toString(16).padStart(2, '0');
  return `#${byte(r)}${byte(g)}${byte(b)}`;
}

function channelToLinear(v: number): number {
  return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
}

export function luminance(hex: string): number {
  const [r, g, b] = hexToRgb(hex).map(channelToLinear) as Rgb;
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** WCAG 2.x contrast ratio. */
export function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}

/** Linear sRGB mix; `weightA` is the share of `a` (0..1). */
export function mix(a: string, b: string, weightA: number): string {
  const ra = hexToRgb(a);
  const rb = hexToRgb(b);
  return rgbToHex(ra.map((v, i) => v * weightA + rb[i]! * (1 - weightA)) as Rgb);
}

// ── HLS (colorsys) ────────────────────────────────────────

function rgbToHls([r, g, b]: Rgb): [number, number, number] {
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  if (max === min) return [0, l, 0];
  const d = max - min;
  const s = l <= 0.5 ? d / (max + min) : d / (2 - max - min);
  const rc = (max - r) / d;
  const gc = (max - g) / d;
  const bc = (max - b) / d;
  let h = r === max ? bc - gc : g === max ? 2 + rc - bc : 4 + gc - rc;
  h = ((h / 6) % 1 + 1) % 1;
  return [h, l, s];
}

function hlsToRgb(h: number, l: number, s: number): Rgb {
  if (s === 0) return [l, l, l];
  const m2 = l <= 0.5 ? l * (1 + s) : l + s - l * s;
  const m1 = 2 * l - m2;
  const v = (hue: number) => {
    const x = ((hue % 1) + 1) % 1;
    if (x < 1 / 6) return m1 + (m2 - m1) * x * 6;
    if (x < 0.5) return m2;
    if (x < 2 / 3) return m1 + (m2 - m1) * (2 / 3 - x) * 6;
    return m1;
  };
  return [v(h + 1 / 3), v(h), v(h - 1 / 3)];
}

/** Same hue and saturation, new HLS lightness; `minSaturation` lifts dull tints. */
export function withLightness(hex: string, lightness: number, minSaturation = 0): string {
  const [h, , s] = rgbToHls(hexToRgb(hex));
  return rgbToHex(hlsToRgb(h, lightness, Math.max(s, minSaturation)));
}

// ── OKLab / OKLCH ─────────────────────────────────────────

function toOklab(hex: string): Rgb {
  const [r, g, b] = hexToRgb(hex).map(channelToLinear) as Rgb;
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [
    0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  ];
}

function fromOklab(L: number, a: number, b: number): string {
  const l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s = (L - 0.0894841775 * a - 1.291485548 * b) ** 3;
  const encode = (c: number) => {
    const v = Math.min(1, Math.max(0, c));
    return v <= 0.0031308 ? 12.92 * v : 1.055 * v ** (1 / 2.4) - 0.055;
  };
  return rgbToHex([
    encode(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
    encode(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
    encode(-0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s),
  ]);
}

export function toOklch(hex: string): { l: number; c: number; h: number } {
  const [l, a, b] = toOklab(hex);
  return { l, c: Math.hypot(a, b), h: ((Math.atan2(b, a) * 180) / Math.PI + 360) % 360 };
}

/** Rotate to an OKLCH hue, keeping lightness and chroma (at least `minChroma`). */
export function withHue(hex: string, hue: number, minChroma = 0): string {
  const { l, c } = toOklch(hex);
  const chroma = Math.max(c, minChroma);
  const rad = (hue * Math.PI) / 180;
  return fromOklab(l, chroma * Math.cos(rad), chroma * Math.sin(rad));
}

/** Perceptual distance (OKLab Euclidean × 100; ~2 barely visible, >10 clearly different). */
export function oklabDistance(a: string, b: string): number {
  const pa = toOklab(a);
  const pb = toOklab(b);
  return 100 * Math.hypot(pa[0] - pb[0], pa[1] - pb[1], pa[2] - pb[2]);
}

export function hueDistance(a: string, b: string): number {
  const d = Math.abs(toOklch(a).h - toOklch(b).h) % 360;
  return Math.min(d, 360 - d);
}

// ── Kontrast absichern ────────────────────────────────────

/**
 * Keep the hue, move HLS lightness in small steps (lighter when `lighten`)
 * until the color reaches `min` contrast against every background.
 */
export function fitContrast(color: string, backgrounds: string[], min: number, lighten: boolean): string {
  const [h, startL, s] = rgbToHls(hexToRgb(color));
  const ok = (c: string) => backgrounds.every((bg) => contrast(c, bg) >= min);
  let l = startL;
  let current = color;
  while (!ok(current) && l > 0 && l < 1) {
    l = Math.min(1, Math.max(0, l + (lighten ? 0.005 : -0.005)));
    current = rgbToHex(hlsToRgb(h, l, s));
  }
  return current;
}

/** Mix `fg` toward `toward` as little as needed to reach `min` contrast on every background. */
export function ensureContrast(fg: string, backgrounds: string[], min: number, toward: string): string {
  let weight = 1;
  let current = fg;
  while (backgrounds.some((bg) => contrast(current, bg) < min) && weight > 0) {
    weight = Math.max(0, weight - 0.02);
    current = mix(fg, toward, weight);
  }
  return current;
}
