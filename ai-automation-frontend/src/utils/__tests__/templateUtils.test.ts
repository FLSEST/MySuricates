import { describe, it, expect } from 'vitest';
import { resolveTemplateName } from '../templateUtils';

describe('resolveTemplateName', () => {
  it('takes the UI language', () => {
    expect(resolveTemplateName({ de: 'Freigabe', en: 'Approval' }, 'de')).toBe('Freigabe');
    expect(resolveTemplateName({ de: 'Freigabe', en: 'Approval' }, 'en')).toBe('Approval');
  });

  it('falls back like the backend: English, then German, then whatever is there', () => {
    expect(resolveTemplateName({ de: 'Freigabe', en: 'Approval' }, 'fr')).toBe('Approval');
    expect(resolveTemplateName({ de: 'Freigabe' }, 'en')).toBe('Freigabe');
    expect(resolveTemplateName({ it: 'Approvazione' }, 'en')).toBe('Approvazione');
    expect(resolveTemplateName({}, 'en')).toBe('');
  });
});
