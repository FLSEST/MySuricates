import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, waitFor } from '@testing-library/react';
import { MonacoEditor } from '../MonacoEditor';

vi.mock('../monacoSetup', () => ({ ensureLocalMonaco: () => Promise.resolve(), registerJsonSchema: () => () => {} }));

/**
 * Stand-in for @monaco-editor/react with the behaviour that matters here: a model, `onChange` on
 * every model change, and — as in the library — a `value` prop that differs from the model
 * replaces it. jsdom cannot run Monaco itself.
 */
const fake = vi.hoisted(() => {
  const state = {
    text: '',
    options: {} as Record<string, unknown>,
    onChange: undefined as ((text: string) => void) | undefined,
    silent: false,
    mounted: false,
  };
  const change = (text: string) => {
    state.text = text;
    if (!state.silent) state.onChange?.(text);
  };
  const editor = {
    getValue: () => state.text,
    setValue: (text: string) => change(text),
    executeEdits: (_source: string, edits: Array<{ text: string }>) => { change(edits[0]!.text); return true; },
    pushUndoStop: () => {},
    getModel: () => ({ uri: 'fake', getFullModelRange: () => ({}), findMatches: () => [] }),
    updateOptions: (options: Record<string, unknown>) => { state.options = { ...state.options, ...options }; },
    revealRangeInCenter: () => {},
    setSelection: () => {},
    deltaDecorations: () => [],
    getAction: () => null,
    focus: () => {},
  };
  /** A keystroke: the model changes first, the parent hears of it through onChange. */
  const type = (chars: string) => change(state.text + chars);
  return { state, editor, type };
});

vi.mock('@monaco-editor/react', async () => {
  const { useEffect, useRef } = await import('react');
  function FakeMonaco(props: {
    value?: string;
    defaultValue?: string;
    options?: Record<string, unknown>;
    onChange?: (text: string) => void;
    onMount?: (editor: unknown, monaco: unknown) => void;
  }) {
    const first = useRef(true);
    useEffect(() => {
      fake.state.text = props.value ?? props.defaultValue ?? '';
      fake.state.options = { ...props.options };
      props.onMount?.(fake.editor, {});
      fake.state.mounted = true;
      // mount only, like the library
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    useEffect(() => {
      fake.state.onChange = props.onChange ? (text) => props.onChange!(text) : undefined;
    });
    useEffect(() => {
      if (first.current) { first.current = false; return; }
      fake.state.options = { ...fake.state.options, ...props.options };
    }, [props.options]);
    useEffect(() => {
      // the library's controlled value: a differing `value` replaces the model, without onChange
      if (props.value === undefined || props.value === fake.state.text) return;
      fake.state.silent = true;
      fake.editor.executeEdits('', [{ text: props.value }]);
      fake.state.silent = false;
    }, [props.value]);
    return <div data-testid="fake-monaco" />;
  }
  return { default: FakeMonaco };
});

beforeEach(() => {
  fake.state.text = '';
  fake.state.options = {};
  fake.state.onChange = undefined;
  fake.state.mounted = false;
});

describe('MonacoEditor', () => {
  it('keeps what was typed when the parent echoes an older keystroke', async () => {
    const onChange = vi.fn();
    const { rerender } = render(<MonacoEditor value="" onChange={onChange} language="markdown" />);
    await waitFor(() => expect(fake.state.mounted).toBe(true));

    act(() => { fake.type('a'); fake.type('b'); fake.type('c'); });
    expect(onChange).toHaveBeenLastCalledWith('abc');

    // the parent's render of the first keystroke arrives after the third one
    rerender(<MonacoEditor value="a" onChange={onChange} language="markdown" />);
    expect(fake.state.text).toBe('abc');
    rerender(<MonacoEditor value="ab" onChange={onChange} language="markdown" />);
    expect(fake.state.text).toBe('abc');
    rerender(<MonacoEditor value="abc" onChange={onChange} language="markdown" />);
    expect(fake.state.text).toBe('abc');
  });

  it('takes a value from outside (loaded, reset, set by another field) without echoing it back', async () => {
    const onChange = vi.fn();
    const { rerender } = render(<MonacoEditor value='{"command": ""}' onChange={onChange} />);
    await waitFor(() => expect(fake.state.mounted).toBe(true));
    expect(fake.state.text).toBe('{"command": ""}');

    rerender(<MonacoEditor value='{"command": "", "provider": "CODEX"}' onChange={onChange} />);
    expect(fake.state.text).toBe('{"command": "", "provider": "CODEX"}');
    expect(onChange).not.toHaveBeenCalled();

    act(() => fake.type(' '));
    expect(onChange).toHaveBeenLastCalledWith('{"command": "", "provider": "CODEX"} ');
  });

  it('updates a read-only viewer', async () => {
    const { rerender } = render(<MonacoEditor value="v1" readOnly />);
    await waitFor(() => expect(fake.state.mounted).toBe(true));
    rerender(<MonacoEditor value="v2" readOnly />);
    expect(fake.state.text).toBe('v2');
  });

  it('prose switches word suggestions off once mounted, so Enter breaks the line', async () => {
    render(<MonacoEditor value="" onChange={() => {}} language="markdown" prose />);
    await waitFor(() => expect(fake.state.mounted).toBe(true));
    expect(fake.state.options).toMatchObject({
      wordWrap: 'on', quickSuggestions: false, wordBasedSuggestions: 'off', suggestOnTriggerCharacters: false,
    });
  });

  it('keeps suggestions for code', async () => {
    render(<MonacoEditor value="{}" onChange={() => {}} />);
    await waitFor(() => expect(fake.state.mounted).toBe(true));
    expect(fake.state.options.quickSuggestions).toMatchObject({ other: true });
  });
});

const SOURCES = import.meta.glob<string>(
  ['../../../**/*.{ts,tsx}', '!../../../**/__tests__/**'],
  { query: '?raw', import: 'default', eager: true },
);

describe('Monaco fields', () => {
  // The fixes above live in the wrapper; a field that uses the library directly misses them.
  // OnboardingProfiles still does (own model per profile and language via `path`) — change this
  // list only on purpose.
  it('go through the wrapper', () => {
    const direct = Object.entries(SOURCES)
      .filter(([, source]) => /from ['"]@monaco-editor\/react['"]|import\(['"]@monaco-editor\/react['"]\)/.test(source))
      .map(([file]) => file.split('/').pop())
      .sort();
    expect(direct).toEqual(['MonacoEditor.tsx', 'OnboardingProfiles.tsx', 'monacoSetup.ts']);
  });

  // Markdown in Monaco is prose here: without `prose`, Enter at the end of a word that already
  // occurs in the text accepts a suggestion instead of breaking the line (9476d6af).
  const markdownEditors = Object.entries(SOURCES)
    .filter(([, source]) => /from ['"][./]*(components\/)?shared\/MonacoEditor['"]/.test(source))
    .flatMap(([file, source]) =>
      [...source.matchAll(/<MonacoEditor\b[\s\S]*?\/>/g)]
        .map((match) => match[0])
        .filter((element) => element.includes('language="markdown"'))
        .map((element) => ({ file, element })),
    );

  it('in Markdown are found', () => {
    const files = markdownEditors.map((e) => e.file);
    expect(files.some((f) => f.endsWith('/InstructionsModal.tsx'))).toBe(true);
    expect(files.some((f) => f.endsWith('/SkillEditorModal.tsx'))).toBe(true);
  });

  it('in Markdown all use prose', () => {
    const withoutProse = markdownEditors.filter((e) => !/\bprose\b/.test(e.element)).map((e) => e.file);
    expect(withoutProse).toEqual([]);
  });
});
