import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// CSP guard: index.html must contain no Google Fonts links and no inline script
// (without src attribute). Both violate a strict Content-Security-Policy.
// See the maintainer's decision (2026-09-15, TODO bdd164a1): system font stack instead of
// Google Fonts, Vite `define: { global: 'globalThis' }` instead of inline shim.
describe('index.html CSP guard', () => {
  const currentDir = path.dirname(fileURLToPath(import.meta.url));
  const indexHtmlPath = path.resolve(currentDir, '../../index.html');
  const html = readFileSync(indexHtmlPath, 'utf-8');

  it('loads no Google Fonts stylesheets or preconnects', () => {
    expect(html).not.toMatch(/fonts\.googleapis\.com/);
    expect(html).not.toMatch(/fonts\.gstatic\.com/);
  });

  it('contains no inline script (only <script src=…> is allowed)', () => {
    const scriptTags = html.match(/<script\b[^>]*>/g) ?? [];
    for (const tag of scriptTags) {
      expect(tag).toMatch(/\bsrc=/);
    }
  });
});
