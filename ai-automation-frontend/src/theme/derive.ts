/**
 * Theme derivation from the official palette + roles of the theme authors.
 *
 * Rules (result of a blind evaluation, see docs/frontend.md → Themes):
 * - Surfaces, borders and text are the colors the author provides for that role;
 *   if the contrast is not enough, they are mixed minimally toward the main text.
 * - Accent/status/roles: surface = original tone (graphics ≥ 3:1), text = only lightened/darkened
 *   as far as needed for 4.5:1.
 * - Status colors are functional: if the palette tone is not in the recognizable
 *   hue range (e.g. an olive-green "success"), only the hue is rotated.
 * - Tailwind color classes (blue-500 …) are redirected to the palette per theme.
 */
import {
  contrast,
  ensureContrast,
  fitContrast,
  hueDistance,
  luminance,
  mix,
  oklabDistance,
  toOklch,
  withHue,
  withLightness,
} from './color';
import type { ThemeTokens } from './tokens';
import type { ThemeAppearance, ThemeDefinition } from './themes';

export type HueFamily = 'red' | 'orange' | 'yellow' | 'green' | 'teal' | 'blue' | 'purple' | 'pink';

export interface PaletteRoles {
  bg: string;
  secondary: string;
  raised: string;
  overlay: string;
  hover: string;
  border: string;
  borderStrong: string;
  text: string;
  text2: string;
  text3: string;
  accent: string;
  accentHover: string;
  success: string;
  warning: string;
  danger: string;
  sidebar: string;
  sidebarBorder: string;
  sidebarHover: string;
  sidebarActive: string;
  sidebarText: string;
  sidebarMuted: string;
  assistant: string;
  assistantRaised: string;
  assistantBorder: string;
  /** Text on solid accent buttons; defaults to `bg`. */
  inverse?: string;
  /** Author's link color when it differs from the button color (Catppuccin, Rosé Pine). */
  link?: string;
}

export interface PaletteThemeSpec {
  id: string;
  name: string;
  appearance: ThemeAppearance;
  roles: PaletteRoles;
  hues: Record<HueFamily, string>;
  /** True where the authors separate panels by visible borders instead of shade. */
  cardBorders: boolean;
}

const SUBTLE_WEIGHT = 0.16;

/** Recognizable OKLCH hue range per meaning, and the hue used when the palette tone falls outside. */
const STATUS_HUES = {
  success: { from: 125, to: 158, target: 145 },
  warning: { from: 45, to: 100, target: 75 },
  danger: { from: 345, to: 40, target: 25 }, // wraps around 0°
} as const;

function functionalStatus(color: string, role: keyof typeof STATUS_HUES): string {
  const { from, to, target } = STATUS_HUES[role];
  const { h } = toOklch(color);
  const inside = from < to ? h >= from && h <= to : h >= from || h <= to;
  return inside ? color : withHue(color, target, 0.06);
}

/** Of the palette's green and teal, the one that reads most like green (OKLCH ≈ 145°). */
function greenest(hues: Record<HueFamily, string>): string {
  const off = (c: string) => Math.abs(toOklch(c).h - 145);
  const { green, teal } = hues;
  return off(teal) + 5 < off(green) && off(teal) < 40 ? teal : green;
}

/**
 * Categorical series colors: palette hues in a fixed order (red/yellow last — they read as error/warning),
 * already made visible, clearly apart from the accent and each other.
 */
function chartColors(hues: Record<HueFamily, string>, accent: string, visible: (c: string) => string): string[] {
  const order: HueFamily[] = ['blue', 'purple', 'teal', 'pink', 'green', 'orange', 'yellow', 'red'];
  let chosen: string[] = [];
  for (const minDistance of [12, 8, 6, 0]) {
    chosen = [accent];
    for (const family of order) {
      const c = visible(hues[family]);
      if (chosen.every((x) => oklabDistance(c, x) > minDistance)) chosen.push(c);
    }
    if (chosen.length >= 6) break;
  }
  const series = chosen.slice(1);
  return Array.from({ length: 8 }, (_, i) => series[i % series.length]!);
}

/** Role colors ai/human/info: preferred palette family, else the family farthest from accent and status. */
function roleColor(preferred: HueFamily[], hues: Record<HueFamily, string>, taken: string[]): string {
  const candidates: HueFamily[] = [...preferred, 'purple', 'teal', 'blue', 'pink', 'orange'];
  const spread = (c: string) => Math.min(...taken.map((t) => oklabDistance(c, t)));
  const good = candidates.find((f) => spread(hues[f]) >= 10);
  if (good) return hues[good];
  return candidates.map((f) => hues[f]).sort((a, b) => spread(b) - spread(a))[0]!;
}

export function buildPaletteTheme(spec: PaletteThemeSpec): ThemeDefinition {
  const dark = spec.appearance === 'dark';
  const r = spec.roles;
  const hues = { ...spec.hues };
  const surfaces = [r.bg, r.secondary, r.raised];

  const text2 = ensureContrast(r.text2, [...surfaces, r.overlay, r.assistant, r.assistantRaised], 4.5, r.text);
  const text3 = ensureContrast(r.text3, surfaces, 3.8, r.text);
  const sidebarMuted = ensureContrast(r.sidebarMuted, [r.sidebar, r.sidebarHover], 4.5, r.sidebarText);
  const border = ensureContrast(r.border, [r.bg, r.raised], 1.3, r.borderStrong);
  const borderStrong = ensureContrast(r.borderStrong, [r.bg, r.raised], 1.6, r.text);

  // Status: functional hue, fill ≥ 3:1 as graphic
  const success = functionalStatus(greenest(hues), 'success');
  hues.green = success;
  const status = {
    success,
    warning: functionalStatus(r.warning, 'warning'),
    danger: functionalStatus(r.danger, 'danger'),
  };
  // An accent that sits on the error hue reads as "error" — the author's link color takes over
  let accent = r.accent;
  if (r.link && hueDistance(accent, status.danger) < 22 && toOklch(accent).c > 0.07) accent = r.link;

  const fill = (c: string) => fitContrast(c, surfaces, 3, dark);
  const readable = (source: string, subtle: string) => fitContrast(source, [...surfaces, subtle], 4.5, dark);

  const tokens: Partial<ThemeTokens> = {};
  const setColor = (name: string, source: string, textSource = source) => {
    const f = fill(source);
    const subtle = mix(f, r.bg, SUBTLE_WEIGHT);
    Object.assign(tokens, {
      [`--color-${name}`]: f,
      [`--color-${name}-subtle`]: subtle,
      [`--color-${name}-text`]: readable(textSource, subtle),
    });
    return f;
  };

  const accentFill = setColor('accent', accent, r.link ?? accent);
  const successFill = setColor('success', status.success);
  setColor('warning', status.warning);
  const dangerFill = setColor('danger', status.danger);
  const taken = [accentFill, status.success, status.warning, status.danger];
  const ai = setColor('ai', roleColor(['purple'], hues, taken));
  const human = setColor('human', roleColor(['teal'], hues, [...taken, ai]));
  setColor('info', roleColor(['blue'], hues, [...taken, ai, human]));

  // Solid accent buttons: emphasis variant that carries the inverse text
  const inverse = r.inverse ?? r.bg;
  const lightenForInverse = luminance(inverse) < luminance(accentFill);
  const emphasisFor = (fill: string) => fitContrast(fill, [inverse], 4.5, luminance(inverse) < luminance(fill));
  const emphasis = fitContrast(accentFill, [inverse], 4.5, lightenForInverse);
  let accentHover = fitContrast(fitContrast(r.accentHover, [inverse], 4.5, lightenForInverse), [r.bg], 4.5, dark);
  if (contrast(accentHover, inverse) < 4.5) accentHover = emphasis;

  chartColors(hues, accentFill, fill).forEach((c, i) => {
    tokens[`--color-chart-${i + 1}` as keyof ThemeTokens] = c;
  });

  Object.assign(tokens, {
    '--color-surface': r.bg,
    '--color-surface-secondary': r.secondary,
    '--color-surface-raised': r.raised,
    '--color-surface-overlay': r.overlay,
    '--color-surface-hover': r.hover,
    '--color-border': border,
    '--color-border-strong': borderStrong,
    '--color-card-border': spec.cardBorders ? border : r.raised,
    '--color-text': r.text,
    '--color-text-secondary': text2,
    '--color-text-tertiary': text3,
    '--color-text-inverse': inverse,
    '--color-accent-hover': accentHover,
    '--color-accent-emphasis': emphasis,
    // Solid danger/success fills carrying text or an icon (danger button, violated seal, done check)
    '--color-danger-emphasis': emphasisFor(dangerFill),
    '--color-success-emphasis': emphasisFor(successFill),
    '--color-sidebar': r.sidebar,
    '--color-sidebar-border': r.sidebarBorder,
    '--color-sidebar-hover': r.sidebarHover,
    '--color-sidebar-active': r.sidebarActive,
    '--color-sidebar-text': r.sidebarText,
    '--color-sidebar-text-muted': sidebarMuted,
    '--color-assistant': r.assistant,
    '--color-assistant-raised': r.assistantRaised,
    '--color-assistant-border': r.assistantBorder,
  });

  return { id: spec.id, name: spec.name, appearance: spec.appearance, tokens: tokens as ThemeTokens, hues };
}

// ── Redirect Tailwind colour classes to the palette ─────────

const TAILWIND_FAMILIES: Record<HueFamily, string[]> = {
  red: ['red'],
  orange: ['orange'],
  yellow: ['amber', 'yellow'],
  green: ['lime', 'green', 'emerald'],
  teal: ['teal', 'cyan'],
  blue: ['sky', 'blue', 'indigo'],
  purple: ['violet', 'purple', 'fuchsia'],
  pink: ['pink', 'rose'],
};
const TAILWIND_NEUTRALS = ['slate', 'gray', 'zinc', 'neutral', 'stone'];
const SHADES = [50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 950] as const;
type Shade = (typeof SHADES)[number];

/** HLS lightness of the Tailwind shades (measured on Tailwind's green scale). */
const SHADE_LIGHTNESS: Record<Exclude<Shade, 500>, number> = {
  50: 0.96, 100: 0.92, 200: 0.85, 300: 0.74, 400: 0.6, 600: 0.37, 700: 0.3, 800: 0.24, 900: 0.2, 950: 0.11,
};

/** Every custom property the remap may set — removed again when switching to a theme without palette. */
export const TAILWIND_PALETTE_VARS: readonly string[] = [
  ...Object.values(TAILWIND_FAMILIES).flat(),
  ...TAILWIND_NEUTRALS,
].flatMap((name) => SHADES.map((shade) => `--color-${name}-${shade}`));

function hueScale(base: string, tokens: ThemeTokens, dark: boolean): Record<Shade, string> {
  const raised = tokens['--color-surface-raised'];
  const cards = [tokens['--color-surface'], raised];
  const scale = {} as Record<Shade, string>;
  for (const shade of SHADES) {
    scale[shade] = shade === 500 ? base : withLightness(base, SHADE_LIGHTNESS[shade]);
  }
  if (dark) {
    // dark tints (bg-green-900/20 …) mixed with the card keep the hue instead of turning olive-brown
    scale[800] = mix(base, raised, 0.6);
    scale[900] = mix(base, raised, 0.45);
    scale[950] = mix(base, raised, 0.35);
  } else {
    for (const shade of [50, 100, 200] as const) scale[shade] = withLightness(base, SHADE_LIGHTNESS[shade], 0.5);
  }
  const tints = dark ? [mix(scale[900], raised, 0.4), mix(scale[800], raised, 0.3)] : [scale[50], scale[100]];
  const textShades = dark ? ([100, 200, 300, 400] as const) : ([600, 700, 800, 900] as const);
  for (const shade of textShades) scale[shade] = fitContrast(scale[shade], [...cards, ...tints], 4.5, dark);
  scale[500] = fitContrast(base, [...cards, mix(base, raised, 0.15)], 3, dark);
  return scale;
}

function neutralScale(tokens: ThemeTokens, dark: boolean): Record<Shade, string> {
  const surface = tokens['--color-surface'];
  const text = tokens['--color-text'];
  const [lightEnd, darkEnd] = dark ? [text, surface] : [surface, text];
  const cards = [surface, tokens['--color-surface-raised']];
  const scale = {} as Record<Shade, string>;
  SHADES.forEach((shade, i) => {
    scale[shade] = mix(darkEnd, lightEnd, i / (SHADES.length - 1));
  });
  for (const shade of [400, 500, 600] as const) scale[shade] = fitContrast(scale[shade], cards, 3, dark);
  return scale;
}

/** Custom properties that remap Tailwind's palette classes onto the theme's palette. */
export function tailwindPaletteVars(theme: ThemeDefinition, tokens: ThemeTokens = theme.tokens): Record<string, string> {
  if (!theme.hues) return {};
  const dark = theme.appearance === 'dark';
  const vars: Record<string, string> = {};
  for (const [family, names] of Object.entries(TAILWIND_FAMILIES) as [HueFamily, string[]][]) {
    const scale = hueScale(theme.hues[family], tokens, dark);
    for (const name of names) for (const shade of SHADES) vars[`--color-${name}-${shade}`] = scale[shade];
  }
  const neutrals = neutralScale(tokens, dark);
  for (const name of TAILWIND_NEUTRALS) for (const shade of SHADES) vars[`--color-${name}-${shade}`] = neutrals[shade];
  return vars;
}
