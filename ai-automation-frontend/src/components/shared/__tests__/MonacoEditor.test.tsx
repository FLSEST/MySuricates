import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useState } from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MonacoEditor } from '../MonacoEditor';

vi.mock('../monacoSetup', () => ({ ensureLocalMonaco: () => Promise.resolve(), registerJsonSchema: () => () => {} }));

/**
 * Stand-in for @monaco-editor/react with the behaviour that matters here: a model, `onChange` on
 * every model change, and — as in the library — a `value` prop that differs from the model
 * replaces it, and one model per `path` (a new path gets a model from `defaultValue`, an old one
 * comes back as it was left). jsdom cannot run Monaco itself.
 */
const fake = vi.hoisted(() => {
  const state = {
    text: '',
    options: {} as Record<string, unknown>,
    onChange: undefined as ((text: string) => void) | undefined,
    silent: false,
    mounted: false,
    path: undefined as string | undefined,
    /** Text of the models not shown, by path. */
    models: new Map<string, string>(),
    /** Typed right after the mount, before React re-renders the wrapper with `mounted`. */
    typeOnMount: [] as string[],
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
    path?: string;
    options?: Record<string, unknown>;
    onChange?: (text: string) => void;
    onMount?: (editor: unknown, monaco: unknown) => void;
  }) {
    const first = useRef(true);
    const shownPath = useRef(props.path);
    useEffect(() => {
      fake.state.text = props.value ?? props.defaultValue ?? '';
      fake.state.path = props.path;
      fake.state.options = { ...props.options };
      props.onMount?.(fake.editor, {});
      fake.state.mounted = true;
      // mount only, like the library
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    useEffect(() => {
      // the library's model switch: keep the old model, show the one of the new path
      if (shownPath.current === props.path) return;
      if (shownPath.current !== undefined) fake.state.models.set(shownPath.current, fake.state.text);
      shownPath.current = props.path;
      fake.state.path = props.path;
      fake.state.text = props.path !== undefined && fake.state.models.has(props.path)
        ? fake.state.models.get(props.path)!
        : props.defaultValue || props.value || '';
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [props.path]);
    useEffect(() => {
      fake.state.onChange = props.onChange ? (text) => props.onChange!(text) : undefined;
    });
    useEffect(() => {
      // same commit as the mount, once onChange is wired: React has not re-rendered the wrapper yet
      for (const chars of fake.state.typeOnMount) fake.type(chars);
    }, []);
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
  fake.state.path = undefined;
  fake.state.models.clear();
  fake.state.typeOnMount = [];
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

  // Under load the test above sometimes typed before the wrapper had seen the mount, and its first
  // sync wrote the stale "" over "abc" (e1075575). The fake types in exactly that window here.
  it('keeps keystrokes that arrive before the wrapper has seen the mount', async () => {
    fake.state.typeOnMount = ['a', 'b', 'c'];
    const onChange = vi.fn();
    const { rerender } = render(<MonacoEditor value="" onChange={onChange} language="markdown" />);
    await waitFor(() => expect(fake.state.mounted).toBe(true));
    await waitFor(() => expect(onChange).toHaveBeenLastCalledWith('abc'));
    expect(fake.state.text).toBe('abc');

    rerender(<MonacoEditor value="a" onChange={onChange} language="markdown" />);
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

  it('shows line numbers unless they are switched off', async () => {
    const { unmount } = render(<MonacoEditor value="" onChange={() => {}} />);
    await waitFor(() => expect(fake.state.mounted).toBe(true));
    expect(fake.state.options.lineNumbers).toBe('on');
    unmount();

    fake.state.mounted = false;
    render(<MonacoEditor value="" onChange={() => {}} language="markdown" prose lineNumbers="off" />);
    await waitFor(() => expect(fake.state.mounted).toBe(true));
    expect(fake.state.options.lineNumbers).toBe('off');
  });

  // OnboardingProfiles edits one text per language in one editor (87c5e9f3): each path keeps its own
  // model, and what was typed lands in the language it was typed in.
  it('keeps one model per path while the parent switches between documents', async () => {
    function TwoLanguages() {
      const [lang, setLang] = useState<'de' | 'en'>('de');
      const [content, setContent] = useState({ de: 'Hallo', en: 'Hello' });
      return (
        <>
          <button type="button" onClick={() => setLang(lang === 'de' ? 'en' : 'de')}>switch</button>
          <output data-testid="state">{JSON.stringify(content)}</output>
          <MonacoEditor
            value={content[lang]}
            onChange={(text) => setContent((c) => ({ ...c, [lang]: text }))}
            language="markdown"
            prose
            path={`profile-${lang}.md`}
          />
        </>
      );
    }
    render(<TwoLanguages />);
    await waitFor(() => expect(fake.state.mounted).toBe(true));
    expect(fake.state.path).toBe('profile-de.md');

    act(() => { fake.type(' W'); fake.type('elt'); });
    fireEvent.click(screen.getByText('switch'));
    expect(fake.state.path).toBe('profile-en.md');
    expect(fake.state.text).toBe('Hello');

    act(() => fake.type(' world'));
    fireEvent.click(screen.getByText('switch'));
    expect(fake.state.text).toBe('Hallo Welt');
    expect(JSON.parse(screen.getByTestId('state').textContent!)).toEqual({ de: 'Hallo Welt', en: 'Hello world' });
  });

  it('uses no own path without schema or path, as before', async () => {
    render(<MonacoEditor value="" onChange={() => {}} />);
    await waitFor(() => expect(fake.state.mounted).toBe(true));
    expect(fake.state.path).toBeUndefined();
  });
});

const SOURCES = import.meta.glob<string>(
  ['../../../**/*.{ts,tsx}', '!../../../**/__tests__/**'],
  { query: '?raw', import: 'default', eager: true },
);

describe('Monaco fields', () => {
  // The fixes above live in the wrapper; a field that uses the library directly misses them.
  // No exception left since OnboardingProfiles moved to the wrapper (87c5e9f3) — change this list
  // only on purpose.
  it('go through the wrapper', () => {
    const direct = Object.entries(SOURCES)
      .filter(([, source]) => /from ['"]@monaco-editor\/react['"]|import\(['"]@monaco-editor\/react['"]\)/.test(source))
      .map(([file]) => file.split('/').pop())
      .sort();
    expect(direct).toEqual(['MonacoEditor.tsx', 'monacoSetup.ts']);
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
    // The onboarding profile editor (moved out of pages/OnboardingProfiles.tsx)
    expect(files.some((f) => f.endsWith('/onboarding/ProfileEditor.tsx'))).toBe(true);
  });

  it('in Markdown all use prose', () => {
    const withoutProse = markdownEditors.filter((e) => !/\bprose\b/.test(e.element)).map((e) => e.file);
    expect(withoutProse).toEqual([]);
  });
});
