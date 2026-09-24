import { describe, it, expect } from 'vitest';
import { categoryLabel, resolveTemplateName } from '../templateUtils';
import { de } from '../../i18n/de';
import { en } from '../../i18n/en';

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

describe('categoryLabel', () => {
  it('names a known category in the UI language', () => {
    expect(categoryLabel(de, 'DATA')).toBe('Daten');
    expect(categoryLabel(en, 'DATA')).toBe('Data');
  });

  it('shows a category it does not know as is', () => {
    expect(categoryLabel(de, 'GARDENING')).toBe('GARDENING');
  });
});
