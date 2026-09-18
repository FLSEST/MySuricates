import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// CSP-Wächter: index.html darf keine Google-Fonts-Links und kein Inline-Script
// (ohne src-Attribut) enthalten. Beides verletzt eine strikte Content-Security-Policy.
// Siehe Entscheidung Flo (2026-09-15): System-Fontstack statt Google Fonts,
// Vite `define: { global: 'globalThis' }` statt Inline-Shim.
describe('index.html CSP guard', () => {
  const currentDir = path.dirname(fileURLToPath(import.meta.url));
  const indexHtmlPath = path.resolve(currentDir, '../../index.html');
  const html = readFileSync(indexHtmlPath, 'utf-8');

  it('lädt keine Google-Fonts-Stylesheets oder -Preconnects', () => {
    expect(html).not.toMatch(/fonts\.googleapis\.com/);
    expect(html).not.toMatch(/fonts\.gstatic\.com/);
  });

  it('enthält kein Inline-Script (nur <script src=…> ist erlaubt)', () => {
    const scriptTags = html.match(/<script\b[^>]*>/g) ?? [];
    for (const tag of scriptTags) {
      expect(tag).toMatch(/\bsrc=/);
    }
  });
});
