export interface ModelOption {
  value: string;
  label: string;
}

/**
 * Returns the recommended model presets for a given AI provider.
 * For PI the values use the "<backend>/<model-id>" routing convention; the
 * field is also free-text — users can type any backend/model combo pi supports.
 */
export function modelOptionsForProvider(provider: string | null | undefined): ModelOption[] {
  switch ((provider || '').toUpperCase()) {
    case 'CODEX':
      return [
        { value: '', label: 'Standard' },
        { value: 'o3', label: 'o3' },
        { value: 'o4-mini', label: 'o4-mini' },
      ];
    case 'PI':
      return [
        { value: '', label: 'Standard (pi-Default-Backend)' },
        { value: 'anthropic/claude-sonnet-4-6', label: 'Anthropic · Sonnet 4.6' },
        { value: 'anthropic/claude-opus-4-6', label: 'Anthropic · Opus 4.6' },
        { value: 'openai/gpt-4o', label: 'OpenAI · GPT-4o' },
        { value: 'openai/o3', label: 'OpenAI · o3' },
        { value: 'openai-codex/gpt-5.5', label: 'OpenAI Codex (OAuth) · gpt-5.5' },
        { value: 'google/gemini-2.0-flash', label: 'Google · Gemini 2.0 Flash' },
        { value: 'xai/grok-2', label: 'xAI · Grok 2' },
        { value: 'groq/llama-3.3-70b-versatile', label: 'Groq · Llama 3.3 70B' },
        { value: 'cerebras/llama3.1-70b', label: 'Cerebras · Llama 3.1 70B' },
        { value: 'openrouter/meta-llama/llama-3.3-70b-instruct', label: 'OpenRouter · Llama 3.3 70B' },
      ];
    default:
      return [
        { value: '', label: 'Standard (Provider-Standard)' },
        { value: 'claude-haiku-4-5-20251001', label: 'Haiku (schnell)' },
        { value: 'claude-sonnet-4-6', label: 'Sonnet (präzise)' },
        { value: 'claude-opus-4-6', label: 'Opus (komplex)' },
      ];
  }
}

/**
 * Pi accepts any "<backend>/<model-id>" string, not just the curated presets,
 * so the model field renders as free-text input with a datalist of suggestions.
 */
export function isFreeTextModelProvider(provider: string | null | undefined): boolean {
  return (provider || '').toUpperCase() === 'PI';
}
