/**
 * Official palettes and roles of the theme authors (as of 2026-09-14).
 *
 * Sources per theme: the author's style guide/specification, otherwise the official
 * VS Code theme (for Tokyo Night colors.lua). Deviations are commented.
 * The actual tokens are produced in derive.ts.
 */
import { mix } from './color';
import type { PaletteThemeSpec } from './derive';

// ── Nord ── nordtheme.com „colors and palettes“ + VS-Code-Theme: nord0 Basis, nord1 Panels, nord8 Hauptakzent, dezente nord1-Rahmen
const nord = {
  n0: '#2e3440', n1: '#3b4252', n2: '#434c5e', n3: '#4c566a', n4: '#d8dee9', n5: '#e5e9f0', n6: '#eceff4',
  n7: '#8fbcbb', n8: '#88c0d0', n9: '#81a1c1', n10: '#5e81ac', n11: '#bf616a', n12: '#d08770', n13: '#ebcb8b', n14: '#a3be8c', n15: '#b48ead',
};
const nordHues = { red: nord.n11, orange: nord.n12, yellow: nord.n13, green: nord.n14, teal: nord.n7, blue: nord.n9, purple: nord.n15, pink: nord.n15 };

// ── Catppuccin ── style-guide.md: base surface, mantle/crust secondary surfaces, surface0-2 elements; VS Code: buttons mauve, links blue
const mocha = {
  pink: '#f5c2e7', mauve: '#cba6f7', red: '#f38ba8', peach: '#fab387', yellow: '#f9e2af', green: '#a6e3a1', teal: '#94e2d5', blue: '#89b4fa',
  lavender: '#b4befe', text: '#cdd6f4', sub0: '#a6adc8', ov1: '#7f849c', s2: '#585b70', s1: '#45475a', s0: '#313244',
  base: '#1e1e2e', mantle: '#181825', crust: '#11111b',
};
const latte = {
  pink: '#ea76cb', mauve: '#8839ef', red: '#d20f39', peach: '#fe640b', yellow: '#df8e1d', green: '#40a02b', teal: '#179299', blue: '#1e66f5',
  lavender: '#7287fd', text: '#4c4f69', sub1: '#5c5f77', ov1: '#8c8fa1', s2: '#acb0be', s0: '#ccd0da',
  base: '#eff1f5', mantle: '#e6e9ef', crust: '#dce0e8',
};

// ── Solarized ── altercation/solarized README: base03/base3 Hintergrund, base02/base2 Hervorhebung, base1/base01 Betonung
const sol = {
  b03: '#002b36', b02: '#073642', b01: '#586e75', b00: '#657b83', b0: '#839496', b1: '#93a1a1', b2: '#eee8d5', b3: '#fdf6e3',
  yellow: '#b58900', orange: '#cb4b16', red: '#dc322f', magenta: '#d33682', violet: '#6c71c4', blue: '#268bd2', cyan: '#2aa198', green: '#859900',
  sidebarDark: '#00212b', borderLight: '#ddd6c1', // Microsofts VS-Code-Theme (Sidebar dunkel / Rahmen hell)
};
const solHues = { red: sol.red, orange: sol.orange, yellow: sol.yellow, green: sol.green, teal: sol.cyan, blue: sol.blue, purple: sol.violet, pink: sol.magenta };
// Solarized text: base1 is too dark for app text, mixed halfway toward base2 (stays on the palette path)
const solTextDark = mix(sol.b1, sol.b2, 0.55);
const solTextLight = mix(sol.b01, sol.b02, 0.5);

// ── Gruvbox ── morhetz/gruvbox (vim): bg0, bg2 popups, bg3 dividers, blue for links; VS Code port: bg0 everywhere + bg1 borders
const gd = {
  bg0: '#282828', bg0s: '#32302f', bg1: '#3c3836', bg2: '#504945', bg3: '#665c54', gray: '#928374', fg1: '#ebdbb2', fg4: '#a89984',
  red: '#fb4934', green: '#b8bb26', yellow: '#fabd2f', blue: '#83a598', purple: '#d3869b', aqua: '#8ec07c', orange: '#fe8019',
};
const gl = {
  bg0h: '#f9f5d7', bg0: '#fbf1c7', bg0s: '#f2e5bc', bg1: '#ebdbb2', bg2: '#d5c4a1', bg3: '#bdae93', fg1: '#3c3836', fg3: '#665c54', fg4: '#7c6f64',
  red: '#9d0006', green: '#79740e', yellow: '#b57614', blue: '#076678', purple: '#8f3f71', aqua: '#427b58', orange: '#af3a03',
};

// ── Rosé Pine ── rosepinetheme.com Rollentabelle: base Apps/Sidebars, surface Panels, overlay Hover, highlightMed Rahmen; Button rose, Link iris
const rp = {
  base: '#191724', surface: '#1f1d2e', overlay: '#26233a', muted: '#6e6a86', subtle: '#908caa', text: '#e0def4', love: '#eb6f92', gold: '#f6c177',
  rose: '#ebbcba', pine: '#31748f', foam: '#9ccfd8', iris: '#c4a7e7', hl: '#21202e', hm: '#403d52', hh: '#524f67',
};
const dawn = {
  base: '#faf4ed', surface: '#fffaf3', overlay: '#f2e9e1', muted: '#9893a5', subtle: '#797593', text: '#464261', love: '#b4637a', gold: '#ea9d34',
  rose: '#d7827e', pine: '#286983', foam: '#56949f', iris: '#907aa9', hm: '#dfdad9', hh: '#cecacd',
};

// ── Dracula ── draculatheme.com/spec: bg, bg_dark sidebars, #343746 floating elements, #44475a selection, comment for subtle borders
const dr = {
  bg: '#282a36', fg: '#f8f8f2', sel: '#44475a', comment: '#6272a4', red: '#ff5555', orange: '#ffb86c', yellow: '#f1fa8c', green: '#50fa7b',
  cyan: '#8be9fd', purple: '#bd93f9', pink: '#ff79c6', floating: '#343746', dark: '#21222c', darker: '#191a21',
};

// ── Everforest ── sainnhe/everforest palette.md: bg_dim dimmed, bg0 default, bg1 cursor line, bg2 popups, grey1 UI borders, green for links/buttons
const ed = {
  dim: '#232a2e', bg0: '#2d353b', bg1: '#343f44', bg2: '#3d484d', bg3: '#475258', fg: '#d3c6aa', red: '#e67e80', orange: '#e69875',
  yellow: '#dbbc7f', green: '#a7c080', aqua: '#83c092', blue: '#7fbbb3', purple: '#d699b6', grey0: '#7a8478', grey1: '#859289', grey2: '#9da9a0',
};
const el = {
  dim: '#efebd4', bg0: '#fdf6e3', bg1: '#f4f0d9', bg2: '#efebd4', bg3: '#e6e2cc', bg4: '#e0dcc7', bg5: '#bdc3af', fg: '#5c6a72', red: '#f85552',
  orange: '#f57d26', yellow: '#dfa000', green: '#8da101', aqua: '#35a77c', blue: '#3a94c5', purple: '#df69ba', grey1: '#939f91', grey2: '#829181',
};
// Everforest light text is too weak for app text: 25 % toward Everforest dark bg0 (only deviation from the palette)
const elText = mix(el.fg, ed.bg0, 0.75);

// ── Tokyo Night ── folke/tokyonight.nvim colors.lua + extras/opencode: bg, bg_dark sidebars, bg_highlight elements, blue primary
const tn = {
  bg: '#1a1b26', dark: '#16161e', hl: '#292e42', fg: '#c0caf5', fgd: '#a9b1d6', gutter: '#3b4261', dark3: '#545c7e', dark5: '#737aa2',
  blue: '#7aa2f7', cyan: '#7dcfff', magenta: '#bb9af7', orange: '#ff9e64', yellow: '#e0af68', green: '#9ece6a', teal: '#1abc9c',
  red: '#f7768e', red1: '#db4b4b', black: '#15161e',
};

export const PALETTE_THEMES: readonly PaletteThemeSpec[] = [
  {
    id: 'nord', name: 'Nord', appearance: 'dark', cardBorders: true, hues: nordHues,
    roles: {
      bg: nord.n0, secondary: mix(nord.n0, nord.n1, 0.5), raised: mix(nord.n0, nord.n1, 0.35), overlay: nord.n1, hover: nord.n1,
      border: nord.n2, borderStrong: nord.n3, text: nord.n4, text2: mix(nord.n4, nord.n0, 0.78), text3: nord.n3,
      accent: nord.n8, accentHover: nord.n7, success: nord.n14, warning: nord.n13, danger: nord.n11,
      sidebar: nord.n0, sidebarBorder: nord.n1, sidebarHover: nord.n1, sidebarActive: nord.n2, sidebarText: nord.n4, sidebarMuted: mix(nord.n4, nord.n0, 0.7),
      assistant: nord.n0, assistantRaised: nord.n1, assistantBorder: nord.n1,
    },
  },
  {
    // Nord "bright ambiance"; there is no official VS Code light theme
    id: 'nord-light', name: 'Nord Light', appearance: 'light', cardBorders: true,
    hues: { ...nordHues, blue: nord.n10 },
    roles: {
      bg: mix(nord.n5, nord.n4, 0.45), secondary: nord.n5, raised: nord.n6, overlay: nord.n6, hover: nord.n4,
      border: mix(nord.n4, nord.n3, 0.82), borderStrong: mix(nord.n4, nord.n3, 0.55), text: nord.n0, text2: nord.n1, text3: nord.n3,
      accent: nord.n10, accentHover: nord.n9, success: nord.n14, warning: nord.n12, danger: nord.n11,
      sidebar: nord.n6, sidebarBorder: nord.n4, sidebarHover: nord.n5, sidebarActive: nord.n4, sidebarText: nord.n0, sidebarMuted: nord.n3,
      assistant: nord.n6, assistantRaised: nord.n5, assistantBorder: nord.n4, inverse: nord.n6,
    },
  },
  {
    id: 'catppuccin-mocha', name: 'Catppuccin Mocha', appearance: 'dark', cardBorders: false,
    hues: { red: mocha.red, orange: mocha.peach, yellow: mocha.yellow, green: mocha.green, teal: mocha.teal, blue: mocha.blue, purple: mocha.mauve, pink: mocha.pink },
    roles: {
      bg: mocha.base, secondary: mocha.mantle, raised: mix(mocha.base, mocha.s0, 0.45), overlay: mocha.s0, hover: mocha.s0,
      border: mocha.s1, borderStrong: mocha.s2, text: mocha.text, text2: mocha.sub0, text3: mocha.ov1,
      accent: mocha.mauve, accentHover: mocha.lavender, success: mocha.green, warning: mocha.yellow, danger: mocha.red,
      sidebar: mocha.mantle, sidebarBorder: mocha.mantle, sidebarHover: mocha.base, sidebarActive: mocha.s0, sidebarText: mocha.text, sidebarMuted: mocha.sub0,
      assistant: mocha.mantle, assistantRaised: mocha.s0, assistantBorder: mocha.s0, inverse: mocha.crust, link: mocha.blue,
    },
  },
  {
    // Cards (base) barely stand out against mantle -> subtle borders despite surface separation
    id: 'catppuccin-latte', name: 'Catppuccin Latte', appearance: 'light', cardBorders: true,
    hues: { red: latte.red, orange: latte.peach, yellow: latte.yellow, green: latte.green, teal: latte.teal, blue: latte.blue, purple: latte.mauve, pink: latte.pink },
    roles: {
      bg: latte.mantle, secondary: latte.crust, raised: latte.base, overlay: latte.base, hover: latte.crust,
      border: latte.s0, borderStrong: latte.s2, text: latte.text, text2: latte.sub1, text3: latte.ov1,
      accent: latte.mauve, accentHover: latte.lavender, success: latte.green, warning: latte.yellow, danger: latte.red,
      sidebar: latte.crust, sidebarBorder: latte.crust, sidebarHover: mix(latte.crust, latte.s0, 0.5), sidebarActive: mix(latte.s0, latte.crust, 0.6),
      sidebarText: latte.text, sidebarMuted: latte.sub1,
      assistant: latte.mantle, assistantRaised: latte.base, assistantBorder: latte.s0, inverse: latte.base, link: latte.blue,
    },
  },
  {
    id: 'solarized-dark', name: 'Solarized Dark', appearance: 'dark', cardBorders: false, hues: solHues,
    roles: {
      bg: sol.b03, secondary: sol.sidebarDark, raised: mix(sol.b03, sol.b02, 0.4), overlay: sol.b02, hover: sol.b02,
      border: mix(sol.b01, sol.b02, 0.35), borderStrong: sol.b01, text: solTextDark, text2: sol.b1, text3: sol.b0,
      accent: sol.blue, accentHover: sol.cyan, success: sol.green, warning: sol.yellow, danger: sol.red,
      sidebar: sol.sidebarDark, sidebarBorder: sol.sidebarDark, sidebarHover: sol.b03, sidebarActive: sol.b02, sidebarText: solTextDark, sidebarMuted: sol.b1,
      assistant: sol.sidebarDark, assistantRaised: sol.b02, assistantBorder: sol.b02, inverse: sol.b03,
    },
  },
  {
    id: 'solarized-light', name: 'Solarized Light', appearance: 'light', cardBorders: true, hues: solHues,
    roles: {
      bg: mix(sol.b2, sol.b3, 0.45), secondary: sol.b2, raised: sol.b3, overlay: sol.b3, hover: mix(sol.b2, sol.b1, 0.85),
      border: sol.borderLight, borderStrong: sol.b1, text: solTextLight, text2: sol.b01, text3: sol.b00,
      accent: sol.blue, accentHover: sol.cyan, success: sol.green, warning: sol.yellow, danger: sol.red,
      sidebar: sol.b2, sidebarBorder: sol.borderLight, sidebarHover: mix(sol.b2, sol.b1, 0.85), sidebarActive: mix(sol.b2, sol.b1, 0.72),
      sidebarText: solTextLight, sidebarMuted: sol.b01,
      assistant: sol.b3, assistantRaised: sol.b2, assistantBorder: sol.borderLight, inverse: sol.b3,
    },
  },
  {
    id: 'gruvbox-dark', name: 'Gruvbox Dark', appearance: 'dark', cardBorders: true,
    hues: { red: gd.red, orange: gd.orange, yellow: gd.yellow, green: gd.green, teal: gd.aqua, blue: gd.blue, purple: gd.purple, pink: gd.purple },
    roles: {
      bg: gd.bg0, secondary: gd.bg0s, raised: mix(gd.bg0, gd.bg0s, 0.5), overlay: gd.bg0s, hover: gd.bg1,
      border: gd.bg2, borderStrong: gd.bg3, text: gd.fg1, text2: gd.fg4, text3: gd.gray,
      accent: gd.blue, accentHover: gd.aqua, success: gd.green, warning: gd.yellow, danger: gd.red,
      sidebar: gd.bg0, sidebarBorder: gd.bg1, sidebarHover: gd.bg1, sidebarActive: gd.bg2, sidebarText: gd.fg1, sidebarMuted: gd.fg4,
      assistant: gd.bg0, assistantRaised: gd.bg0s, assistantBorder: gd.bg1,
    },
  },
  {
    id: 'gruvbox-light', name: 'Gruvbox Light', appearance: 'light', cardBorders: true,
    hues: { red: gl.red, orange: gl.orange, yellow: gl.yellow, green: gl.green, teal: gl.aqua, blue: gl.blue, purple: gl.purple, pink: gl.purple },
    roles: {
      bg: gl.bg0s, secondary: mix(gl.bg0s, gl.bg1, 0.5), raised: gl.bg0, overlay: gl.bg0h, hover: gl.bg1,
      border: gl.bg2, borderStrong: gl.bg3, text: gl.fg1, text2: gl.fg3, text3: gl.fg4,
      accent: gl.blue, accentHover: gl.aqua, success: gl.green, warning: gl.yellow, danger: gl.red,
      sidebar: gl.bg0, sidebarBorder: gl.bg2, sidebarHover: gl.bg1, sidebarActive: gl.bg2, sidebarText: gl.fg1, sidebarMuted: gl.fg3,
      assistant: gl.bg0, assistantRaised: gl.bg0h, assistantBorder: gl.bg2, inverse: gl.bg0,
    },
  },
  {
    id: 'rose-pine', name: 'Rosé Pine', appearance: 'dark', cardBorders: false,
    hues: { red: rp.love, orange: rp.rose, yellow: rp.gold, green: rp.pine, teal: rp.foam, blue: rp.foam, purple: rp.iris, pink: rp.rose },
    roles: {
      bg: rp.base, secondary: rp.hl, raised: rp.surface, overlay: rp.overlay, hover: rp.overlay,
      border: rp.hm, borderStrong: rp.hh, text: rp.text, text2: rp.subtle, text3: rp.muted,
      accent: rp.rose, accentHover: rp.iris, success: rp.pine, warning: rp.gold, danger: rp.love,
      sidebar: rp.base, sidebarBorder: rp.hl, sidebarHover: rp.overlay, sidebarActive: rp.hm, sidebarText: rp.text, sidebarMuted: rp.subtle,
      assistant: rp.base, assistantRaised: rp.surface, assistantBorder: rp.hm, link: rp.iris,
    },
  },
  {
    id: 'rose-pine-dawn', name: 'Rosé Pine Dawn', appearance: 'light', cardBorders: false,
    hues: { red: dawn.love, orange: dawn.rose, yellow: dawn.gold, green: dawn.pine, teal: dawn.foam, blue: dawn.foam, purple: dawn.iris, pink: dawn.rose },
    roles: {
      bg: dawn.base, secondary: dawn.overlay, raised: dawn.surface, overlay: dawn.surface, hover: dawn.overlay,
      border: dawn.hm, borderStrong: dawn.hh, text: dawn.text, text2: dawn.subtle, text3: dawn.muted,
      accent: dawn.rose, accentHover: dawn.iris, success: dawn.pine, warning: dawn.gold, danger: dawn.love,
      sidebar: dawn.base, sidebarBorder: dawn.hm, sidebarHover: dawn.overlay, sidebarActive: dawn.hm, sidebarText: dawn.text, sidebarMuted: dawn.subtle,
      assistant: dawn.base, assistantRaised: dawn.surface, assistantBorder: dawn.hm, inverse: dawn.surface, link: dawn.iris,
    },
  },
  {
    id: 'dracula', name: 'Dracula', appearance: 'dark', cardBorders: false,
    hues: { red: dr.red, orange: dr.orange, yellow: dr.yellow, green: dr.green, teal: dr.cyan, blue: dr.cyan, purple: dr.purple, pink: dr.pink },
    roles: {
      bg: dr.bg, secondary: dr.dark, raised: mix(dr.bg, dr.floating, 0.5), overlay: dr.floating, hover: dr.sel,
      border: dr.sel, borderStrong: dr.comment, text: dr.fg, text2: mix(dr.fg, dr.comment, 0.55), text3: dr.comment,
      accent: dr.purple, accentHover: dr.pink, success: dr.green, warning: dr.orange, danger: dr.red,
      sidebar: dr.dark, sidebarBorder: dr.darker, sidebarHover: dr.bg, sidebarActive: dr.sel, sidebarText: dr.fg, sidebarMuted: mix(dr.fg, dr.comment, 0.5),
      assistant: dr.dark, assistantRaised: dr.bg, assistantBorder: dr.darker,
    },
  },
  {
    id: 'everforest-dark', name: 'Everforest Dark', appearance: 'dark', cardBorders: false,
    hues: { red: ed.red, orange: ed.orange, yellow: ed.yellow, green: ed.green, teal: ed.aqua, blue: ed.blue, purple: ed.purple, pink: ed.purple },
    roles: {
      bg: ed.bg0, secondary: ed.dim, raised: ed.bg1, overlay: ed.bg2, hover: ed.bg2,
      border: ed.bg3, borderStrong: ed.grey0, text: ed.fg, text2: ed.grey2, text3: ed.grey1,
      accent: ed.green, accentHover: ed.aqua, success: ed.aqua, warning: ed.yellow, danger: ed.red,
      sidebar: ed.dim, sidebarBorder: ed.bg0, sidebarHover: ed.bg0, sidebarActive: ed.bg1, sidebarText: ed.fg, sidebarMuted: ed.grey2,
      assistant: ed.dim, assistantRaised: ed.bg0, assistantBorder: ed.bg1,
    },
  },
  {
    id: 'everforest-light', name: 'Everforest Light', appearance: 'light', cardBorders: false,
    hues: { red: el.red, orange: el.orange, yellow: el.yellow, green: el.green, teal: el.aqua, blue: el.blue, purple: el.purple, pink: el.purple },
    roles: {
      bg: el.bg1, secondary: el.bg2, raised: el.bg0, overlay: el.bg0, hover: el.bg3,
      border: el.bg5, borderStrong: el.grey1, text: elText, text2: el.grey2, text3: el.grey1,
      accent: el.green, accentHover: el.aqua, success: el.aqua, warning: el.yellow, danger: el.red,
      sidebar: el.dim, sidebarBorder: el.bg4, sidebarHover: el.bg3, sidebarActive: el.bg4, sidebarText: elText, sidebarMuted: el.grey2,
      assistant: el.bg1, assistantRaised: el.bg0, assistantBorder: el.bg4, inverse: el.bg0,
    },
  },
  {
    // Primer tokens from the official VS Code theme 6.3.5
    id: 'github-dark', name: 'GitHub Dark', appearance: 'dark', cardBorders: true,
    hues: { red: '#f85149', orange: '#db6d28', yellow: '#d29922', green: '#3fb950', teal: '#179b9b', blue: '#2f81f7', purple: '#a371f7', pink: '#d34591' },
    roles: {
      bg: '#0d1117', secondary: '#161b22', raised: '#161b22', overlay: '#1c2128', hover: mix('#6e7681', '#0d1117', 0.14),
      border: '#30363d', borderStrong: '#484f58', text: '#e6edf3', text2: '#7d8590', text3: '#6e7681',
      accent: '#2f81f7', accentHover: '#58a6ff', success: '#3fb950', warning: '#d29922', danger: '#f85149',
      sidebar: '#010409', sidebarBorder: '#21262d', sidebarHover: '#161b22', sidebarActive: mix('#6e7681', '#010409', 0.3),
      sidebarText: '#e6edf3', sidebarMuted: '#7d8590',
      // Button text dark instead of Primer white: white rules out a readable light-blue hover text
      assistant: '#010409', assistantRaised: '#0d1117', assistantBorder: '#30363d',
    },
  },
  {
    id: 'github-light', name: 'GitHub Light', appearance: 'light', cardBorders: true,
    hues: { red: '#cf222e', orange: '#bc4c00', yellow: '#bf8700', green: '#1f883d', teal: '#179b9b', blue: '#0969da', purple: '#8250df', pink: '#bf3989' },
    roles: {
      bg: '#f6f8fa', secondary: '#eaeef2', raised: '#ffffff', overlay: '#ffffff', hover: '#eaeef2',
      border: '#d0d7de', borderStrong: '#afb8c1', text: '#1f2328', text2: '#656d76', text3: '#6e7781',
      accent: '#0969da', accentHover: '#0550ae', success: '#1f883d', warning: '#bf8700', danger: '#cf222e',
      sidebar: '#ffffff', sidebarBorder: '#d0d7de', sidebarHover: '#f6f8fa', sidebarActive: '#eaeef2', sidebarText: '#1f2328', sidebarMuted: '#656d76',
      assistant: '#f6f8fa', assistantRaised: '#ffffff', assistantBorder: '#d0d7de', inverse: '#ffffff',
    },
  },
  {
    id: 'tokyo-night', name: 'Tokyo Night', appearance: 'dark', cardBorders: false,
    hues: { red: tn.red, orange: tn.orange, yellow: tn.yellow, green: tn.green, teal: tn.teal, blue: tn.blue, purple: tn.magenta, pink: tn.magenta },
    roles: {
      bg: tn.bg, secondary: tn.dark, raised: mix(tn.bg, tn.hl, 0.5), overlay: tn.hl, hover: tn.hl,
      border: tn.gutter, borderStrong: tn.dark3, text: tn.fg, text2: tn.fgd, text3: tn.dark5,
      accent: tn.blue, accentHover: tn.cyan, success: tn.green, warning: tn.yellow, danger: tn.red1,
      sidebar: tn.dark, sidebarBorder: tn.black, sidebarHover: tn.bg, sidebarActive: tn.hl, sidebarText: tn.fg, sidebarMuted: tn.fgd,
      assistant: tn.dark, assistantRaised: tn.bg, assistantBorder: tn.hl, inverse: tn.black,
    },
  },
];
