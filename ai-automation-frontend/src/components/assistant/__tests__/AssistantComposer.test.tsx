import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createRef } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { useI18nStore } from '../../../i18n';
import { en } from '../../../i18n/en';
import { AssistantComposer } from '../AssistantComposer';

// jsdom lays nothing out, scrollHeight is always 0: shadow it for the auto-grow test
function stubScrollHeight(px: number) {
  Object.defineProperty(HTMLElement.prototype, 'scrollHeight', { configurable: true, get: () => px });
}

beforeEach(() => useI18nStore.setState({ locale: 'en' }));
afterEach(() => { Reflect.deleteProperty(HTMLElement.prototype, 'scrollHeight'); });

function renderComposer(value: string) {
  const onSend = vi.fn();
  const onChange = vi.fn();
  const inputRef = createRef<HTMLTextAreaElement>();
  const view = render(<AssistantComposer value={value} onChange={onChange} onSend={onSend} inputRef={inputRef} />);
  return { ...view, onSend, onChange, inputRef, input: screen.getByPlaceholderText(en.assistant.inputPlaceholder) };
}

describe('AssistantComposer', () => {
  it('sends on Enter but breaks the line on Shift+Enter', () => {
    const { input, onSend } = renderComposer('Hello');
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: true });
    expect(onSend).not.toHaveBeenCalled();
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onSend).toHaveBeenCalledTimes(1);
  });

  it('passes typing on and only enables the send button with text', () => {
    const { input, onChange, rerender, onSend, inputRef } = renderComposer('   ');
    expect(screen.getByRole('button')).toBeDisabled();
    fireEvent.change(input, { target: { value: 'Hi' } });
    expect(onChange).toHaveBeenCalledWith('Hi');

    rerender(<AssistantComposer value="Hi" onChange={onChange} onSend={onSend} inputRef={inputRef} />);
    fireEvent.click(screen.getByRole('button'));
    expect(onSend).toHaveBeenCalledTimes(1);
    expect(inputRef.current).toBe(input);
  });

  it('grows with a programmatic value up to its maximum height', () => {
    stubScrollHeight(120);
    const { input, rerender, onChange, onSend, inputRef } = renderComposer('one line');
    expect(input.style.height).toBe('120px');

    stubScrollHeight(900);
    rerender(<AssistantComposer value={'many\nlines'} onChange={onChange} onSend={onSend} inputRef={inputRef} />);
    expect(input.style.height).toBe('240px');
  });
});
