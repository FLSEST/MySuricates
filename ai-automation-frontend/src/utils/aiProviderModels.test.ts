import { describe, it, expect } from 'vitest';
import {
  modelOptionsForProvider,
  isFreeTextModelProvider,
  type ModelOption,
} from './aiProviderModels';

describe('aiProviderModels', () => {
  describe('modelOptionsForProvider', () => {
    describe('CODEX provider', () => {
      it('returns codex-specific model options', () => {
        const options = modelOptionsForProvider('CODEX');
        expect(options).toEqual([
          { value: '', label: 'Standard' },
          { value: 'o3', label: 'o3' },
          { value: 'o4-mini', label: 'o4-mini' },
        ]);
      });

      it('is case-insensitive (lowercase)', () => {
        expect(modelOptionsForProvider('codex')).toEqual(modelOptionsForProvider('CODEX'));
      });

      it('is case-insensitive (mixed case)', () => {
        expect(modelOptionsForProvider('Codex')).toEqual(modelOptionsForProvider('CODEX'));
      });

      it('contains a default empty-value option as first entry', () => {
        const [first] = modelOptionsForProvider('CODEX');
        expect(first.value).toBe('');
      });
    });

    describe('PI provider', () => {
      it('returns the curated PI backend/model presets', () => {
        const options = modelOptionsForProvider('PI');
        expect(options).toContainEqual({ value: '', label: 'Standard (pi-Default-Backend)' });
        expect(options).toContainEqual({ value: 'anthropic/claude-sonnet-4-6', label: 'Anthropic · Sonnet 4.6' });
        expect(options).toContainEqual({ value: 'anthropic/claude-opus-4-6', label: 'Anthropic · Opus 4.6' });
        expect(options).toContainEqual({ value: 'openai/gpt-4o', label: 'OpenAI · GPT-4o' });
        expect(options).toContainEqual({ value: 'openai/o3', label: 'OpenAI · o3' });
        expect(options).toContainEqual({ value: 'openai-codex/gpt-5.5', label: 'OpenAI Codex (OAuth) · gpt-5.5' });
        expect(options).toContainEqual({ value: 'google/gemini-2.0-flash', label: 'Google · Gemini 2.0 Flash' });
        expect(options).toContainEqual({ value: 'xai/grok-2', label: 'xAI · Grok 2' });
        expect(options).toContainEqual({ value: 'groq/llama-3.3-70b-versatile', label: 'Groq · Llama 3.3 70B' });
        expect(options).toContainEqual({ value: 'cerebras/llama3.1-70b', label: 'Cerebras · Llama 3.1 70B' });
        expect(options).toContainEqual({ value: 'openrouter/meta-llama/llama-3.3-70b-instruct', label: 'OpenRouter · Llama 3.3 70B' });
      });

      it('uses backend/model routing convention for non-empty values', () => {
        const options = modelOptionsForProvider('PI');
        const nonEmpty = options.filter((o) => o.value !== '');
        nonEmpty.forEach((o) => {
          expect(o.value).toMatch(/\//);
        });
      });

      it('is case-insensitive', () => {
        expect(modelOptionsForProvider('pi')).toEqual(modelOptionsForProvider('PI'));
        expect(modelOptionsForProvider('Pi')).toEqual(modelOptionsForProvider('PI'));
      });
    });

    describe('default / Claude provider (fallback)', () => {
      it('returns Claude model presets when provider is "CLAUDE"', () => {
        const options = modelOptionsForProvider('CLAUDE');
        expect(options).toEqual([
          { value: '', label: 'Standard (Provider-Standard)' },
          { value: 'claude-haiku-4-5-20251001', label: 'Haiku (schnell)' },
          { value: 'claude-sonnet-4-6', label: 'Sonnet (präzise)' },
          { value: 'claude-opus-4-6', label: 'Opus (komplex)' },
        ]);
      });

      it('returns the same defaults for unknown providers', () => {
        const claude = modelOptionsForProvider('CLAUDE');
        expect(modelOptionsForProvider('UNKNOWN_PROVIDER')).toEqual(claude);
        expect(modelOptionsForProvider('GEMINI')).toEqual(claude);
        expect(modelOptionsForProvider('foobar')).toEqual(claude);
      });

      it('falls back to defaults for null', () => {
        const claude = modelOptionsForProvider('CLAUDE');
        expect(modelOptionsForProvider(null)).toEqual(claude);
      });

      it('falls back to defaults for undefined', () => {
        const claude = modelOptionsForProvider('CLAUDE');
        expect(modelOptionsForProvider(undefined)).toEqual(claude);
      });

      it('falls back to defaults for empty string', () => {
        const claude = modelOptionsForProvider('CLAUDE');
        expect(modelOptionsForProvider('')).toEqual(claude);
      });
    });

    describe('return value contract', () => {
      it('returns at least one option for every known provider', () => {
        ['CODEX', 'PI', 'CLAUDE', 'unknown'].forEach((p) => {
          const options = modelOptionsForProvider(p);
          expect(options.length).toBeGreaterThan(0);
        });
      });

      it('every option has a non-empty label', () => {
        ['CODEX', 'PI', 'CLAUDE', null, undefined, ''].forEach((p) => {
          const options = modelOptionsForProvider(p as string | null | undefined);
          options.forEach((o: ModelOption) => {
            expect(o.label).toBeTruthy();
            expect(typeof o.label).toBe('string');
          });
        });
      });

      it('every options array starts with the empty-value default', () => {
        ['CODEX', 'PI', 'CLAUDE', null, undefined].forEach((p) => {
          const options = modelOptionsForProvider(p as string | null | undefined);
          expect(options[0].value).toBe('');
        });
      });
    });

    describe('snapshot regression guards', () => {
      it('matches the CODEX snapshot', () => {
        expect(modelOptionsForProvider('CODEX')).toMatchSnapshot();
      });

      it('matches the PI snapshot', () => {
        expect(modelOptionsForProvider('PI')).toMatchSnapshot();
      });

      it('matches the default (Claude) snapshot', () => {
        expect(modelOptionsForProvider('CLAUDE')).toMatchSnapshot();
      });
    });
  });

  describe('isFreeTextModelProvider', () => {
    it('returns true for PI (uppercase)', () => {
      expect(isFreeTextModelProvider('PI')).toBe(true);
    });

    it('returns true for pi (lowercase)', () => {
      expect(isFreeTextModelProvider('pi')).toBe(true);
    });

    it('returns true for mixed case Pi', () => {
      expect(isFreeTextModelProvider('Pi')).toBe(true);
    });

    it('returns false for CODEX', () => {
      expect(isFreeTextModelProvider('CODEX')).toBe(false);
    });

    it('returns false for CLAUDE', () => {
      expect(isFreeTextModelProvider('CLAUDE')).toBe(false);
    });

    it('returns false for unknown providers', () => {
      expect(isFreeTextModelProvider('GEMINI')).toBe(false);
      expect(isFreeTextModelProvider('foobar')).toBe(false);
    });

    it('returns false for null (covers `??` nullish-coalescing change)', () => {
      expect(isFreeTextModelProvider(null)).toBe(false);
    });

    it('returns false for undefined (covers `??` nullish-coalescing change)', () => {
      expect(isFreeTextModelProvider(undefined)).toBe(false);
    });

    it('returns false for empty string', () => {
      expect(isFreeTextModelProvider('')).toBe(false);
    });
  });
});
