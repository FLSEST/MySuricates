import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MarkdownBlock } from '../MarkdownBlock';

const text = 'Siehe [den Lauf](/workflows/w1/run/r1), [die Doku](https://example.org/docs) und [das](//evil.example).';

describe('MarkdownBlock links', () => {
  it('opens app paths through the handler instead of reloading the page', () => {
    const onInternalLink = vi.fn();
    render(<MarkdownBlock onInternalLink={onInternalLink}>{text}</MarkdownBlock>);
    const internal = screen.getByRole('link', { name: 'den Lauf' });

    const click = new MouseEvent('click', { bubbles: true, cancelable: true });
    internal.dispatchEvent(click);

    expect(click.defaultPrevented).toBe(true);
    expect(onInternalLink).toHaveBeenCalledWith('/workflows/w1/run/r1');
    expect(internal).toHaveAttribute('href', '/workflows/w1/run/r1');
  });

  it('opens other links, protocol-relative ones included, in a new tab', () => {
    const onInternalLink = vi.fn();
    render(<MarkdownBlock onInternalLink={onInternalLink}>{text}</MarkdownBlock>);
    for (const name of ['die Doku', 'das']) {
      const link = screen.getByRole('link', { name });
      expect(link).toHaveAttribute('target', '_blank');
      expect(link).toHaveAttribute('rel', 'noopener noreferrer');
      fireEvent.click(link);
    }
    expect(onInternalLink).not.toHaveBeenCalled();
  });

  it('leaves links as plain anchors without a handler', () => {
    render(<MarkdownBlock>{text}</MarkdownBlock>);
    const internal = screen.getByRole('link', { name: 'den Lauf' });
    expect(internal).not.toHaveAttribute('target');
    const click = new MouseEvent('click', { bubbles: true, cancelable: true });
    internal.dispatchEvent(click);
    expect(click.defaultPrevented).toBe(false);
  });
});
