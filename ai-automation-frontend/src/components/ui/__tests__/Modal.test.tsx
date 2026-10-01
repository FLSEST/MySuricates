import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Modal } from '../Modal';
import { useState } from 'react';
import { useI18nStore } from '../../../i18n';

describe('Modal', () => {
  it('renders nothing when open is false', () => {
    render(<Modal open={false} onClose={vi.fn()} title="Test">Content</Modal>);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('renders dialog when open is true', () => {
    render(<Modal open={true} onClose={vi.fn()} title="Test">Content</Modal>);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('renders title', () => {
    render(<Modal open={true} onClose={vi.fn()} title="My Modal">Content</Modal>);
    expect(screen.getByText('My Modal')).toBeInTheDocument();
  });

  it('renders children content', () => {
    render(<Modal open={true} onClose={vi.fn()} title="T">Body text</Modal>);
    expect(screen.getByText('Body text')).toBeInTheDocument();
  });

  it('renders footer when provided', () => {
    render(
      <Modal open={true} onClose={vi.fn()} title="T" footer={<button>Save</button>}>
        Content
      </Modal>,
    );
    expect(screen.getByText('Save')).toBeInTheDocument();
  });

  it('does not render footer when not provided', () => {
    render(<Modal open={true} onClose={vi.fn()} title="T">Content</Modal>);
    expect(screen.queryByText('Save')).not.toBeInTheDocument();
  });

  it('calls onClose when close button is clicked', () => {
    const onClose = vi.fn();
    render(<Modal open={true} onClose={onClose} title="T">Content</Modal>);
    fireEvent.click(screen.getByLabelText('Close'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('calls onClose when backdrop is clicked', () => {
    const onClose = vi.fn();
    const { container } = render(<Modal open={true} onClose={onClose} title="T">Content</Modal>);
    const backdrop = container.querySelector('.bg-black\\/60');
    fireEvent.click(backdrop!);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('calls onClose when Escape key is pressed', () => {
    const onClose = vi.fn();
    render(<Modal open={true} onClose={onClose} title="T">Content</Modal>);
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('does not call onClose for other key presses', () => {
    const onClose = vi.fn();
    render(<Modal open={true} onClose={onClose} title="T">Content</Modal>);
    fireEvent.keyDown(window, { key: 'Enter' });
    expect(onClose).not.toHaveBeenCalled();
  });

  it('sets body overflow to hidden when open', () => {
    render(<Modal open={true} onClose={vi.fn()} title="T">Content</Modal>);
    expect(document.body.style.overflow).toBe('hidden');
  });

  // 23635ef6: a confirmation can open above another dialog (e.g. deleting from a detail dialog)
  it('closes only the dialog on top with Escape and keeps the page locked until the last one closes', () => {
    const closeOuter = vi.fn();
    const closeInner = vi.fn();
    const { rerender } = render(
      <Modal open onClose={closeOuter} title="Outer">
        <Modal open={false} onClose={closeInner} title="Inner">Sure?</Modal>
      </Modal>,
    );
    rerender(
      <Modal open onClose={closeOuter} title="Outer">
        <Modal open onClose={closeInner} title="Inner">Sure?</Modal>
      </Modal>,
    );

    fireEvent.keyDown(window, { key: 'Escape' });
    expect(closeInner).toHaveBeenCalledTimes(1);
    expect(closeOuter).not.toHaveBeenCalled();

    rerender(
      <Modal open onClose={closeOuter} title="Outer">
        <Modal open={false} onClose={closeInner} title="Inner">Sure?</Modal>
      </Modal>,
    );
    expect(document.body.style.overflow).toBe('hidden');
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(closeOuter).toHaveBeenCalledTimes(1);

    rerender(<Modal open={false} onClose={closeOuter} title="Outer">Content</Modal>);
    expect(document.body.style.overflow).toBe('');
  });

  it('stays fixed-size without resizable (no maximise button)', () => {
    render(<Modal open={true} onClose={vi.fn()} title="Test">Content</Modal>);
    expect(screen.queryByRole('button', { name: /maxim/i })).not.toBeInTheDocument();
    expect(screen.getByRole('dialog').className).not.toMatch(/\bresize\b/);
  });

  it('can be dragged to a new size and maximised when resizable', () => {
    useI18nStore.setState({ locale: 'en' });
    const { rerender } = render(<Modal open={true} onClose={vi.fn()} title="Test" resizable>Content</Modal>);
    const dialog = screen.getByRole('dialog');
    expect(dialog.className).toMatch(/\bresize\b/);

    dialog.style.width = '900px';
    fireEvent.click(screen.getByRole('button', { name: 'Maximize' }));
    expect(dialog.className).toMatch(/w-\[96vw\]/);
    expect(dialog.style.width).toBe('');

    fireEvent.click(screen.getByRole('button', { name: 'Restore size' }));
    expect(dialog.className).toMatch(/\bresize\b/);

    // Closing and reopening starts at the normal size again
    fireEvent.click(screen.getByRole('button', { name: 'Maximize' }));
    rerender(<Modal open={false} onClose={vi.fn()} title="Test" resizable>Content</Modal>);
    rerender(<Modal open={true} onClose={vi.fn()} title="Test" resizable>Content</Modal>);
    expect(screen.getByRole('button', { name: 'Maximize' })).toBeInTheDocument();
  });
  describe('focus (0d9072fa)', () => {
    function Opener({ withInput = true }: { withInput?: boolean }) {
      const [open, setOpen] = useState(false);
      return (
        <>
          <button onClick={() => setOpen(true)}>Open</button>
          <Modal open={open} onClose={() => setOpen(false)} title="T" footer={<button>Save</button>}>
            {withInput ? <input aria-label="Name" /> : 'Text only'}
          </Modal>
        </>
      );
    }

    it('moves the focus to the first control of the body, not to the close button', () => {
      render(<Opener />);
      fireEvent.click(screen.getByText('Open'));
      expect(screen.getByLabelText('Name')).toHaveFocus();
    });

    it('focuses the footer when the body has no control', () => {
      render(<Opener withInput={false} />);
      fireEvent.click(screen.getByText('Open'));
      expect(screen.getByText('Save')).toHaveFocus();
    });

    it('keeps the focus a child set with autoFocus', () => {
      render(
        <Modal open onClose={vi.fn()} title="T">
          <input aria-label="First" />
          <input aria-label="Second" autoFocus />
        </Modal>,
      );
      expect(screen.getByLabelText('Second')).toHaveFocus();
    });

    it('focuses the dialog itself when it has no control at all', () => {
      render(<Modal open onClose={vi.fn()} title="T">Text</Modal>);
      // Only the close button exists: it lies in the header, so the dialog takes the focus
      expect(screen.getByRole('dialog')).toHaveFocus();
    });

    it('keeps Tab and Shift+Tab inside the dialog', () => {
      useI18nStore.setState({ locale: 'en' });
      render(<Opener />);
      fireEvent.click(screen.getByText('Open'));
      const close = screen.getByLabelText('Close');
      const save = screen.getByText('Save');

      save.focus();
      fireEvent.keyDown(save, { key: 'Tab' });
      expect(close).toHaveFocus();

      fireEvent.keyDown(close, { key: 'Tab', shiftKey: true });
      expect(save).toHaveFocus();

      // Focus that slipped out (e.g. a click on the backdrop) comes back with the next Tab
      screen.getByText('Open').focus();
      fireEvent.keyDown(document.activeElement!, { key: 'Tab' });
      expect(close).toHaveFocus();
    });

    it('leaves Tab alone when an editor inside already handled it', () => {
      render(<Opener />);
      fireEvent.click(screen.getByText('Open'));
      const save = screen.getByText('Save');
      save.focus();
      save.addEventListener('keydown', (e) => e.preventDefault(), { once: true });
      fireEvent.keyDown(save, { key: 'Tab' });
      expect(save).toHaveFocus();
    });

    it('returns the focus to the opener when it closes', () => {
      render(<Opener />);
      // A click focuses the button in the browser, fireEvent does not
      screen.getByText('Open').focus();
      fireEvent.click(screen.getByText('Open'));
      expect(screen.getByLabelText('Name')).toHaveFocus();
      fireEvent.keyDown(window, { key: 'Escape' });
      expect(screen.queryByRole('dialog')).toBeNull();
      expect(screen.getByText('Open')).toHaveFocus();
    });

    it('does not take the focus back when the closing action moved it on purpose', () => {
      function Moving() {
        const [open, setOpen] = useState(true);
        return (
          <>
            <input aria-label="Elsewhere" />
            <Modal open={open} onClose={vi.fn()} title="T">
              <button onClick={() => { setOpen(false); (document.querySelector('[aria-label="Elsewhere"]') as HTMLElement).focus(); }}>Done</button>
            </Modal>
          </>
        );
      }
      render(<Moving />);
      fireEvent.click(screen.getByText('Done'));
      expect(screen.getByLabelText('Elsewhere')).toHaveFocus();
    });

    it('treats a dialog opening inside another in the same commit as the top one', () => {
      const closeOuter = vi.fn();
      const closeInner = vi.fn();
      render(
        <Modal open onClose={closeOuter} title="Outer">
          <button>Outer action</button>
          <Modal open onClose={closeInner} title="Inner"><button>Inner action</button></Modal>
        </Modal>,
      );
      expect(screen.getByText('Inner action')).toHaveFocus();

      fireEvent.keyDown(window, { key: 'Escape' });
      expect(closeInner).toHaveBeenCalledTimes(1);
      expect(closeOuter).not.toHaveBeenCalled();

      // Only the inner dialog traps Tab
      const innerClose = screen.getAllByLabelText('Close')[1];
      fireEvent.keyDown(screen.getByText('Inner action'), { key: 'Tab' });
      expect(innerClose).toHaveFocus();
    });

    it('returns the focus into the outer dialog when the inner one closes', () => {
      function Nested() {
        const [inner, setInner] = useState(false);
        return (
          <Modal open onClose={vi.fn()} title="Outer">
            <button onClick={() => setInner(true)}>Delete</button>
            <Modal open={inner} onClose={() => setInner(false)} title="Inner">Sure?</Modal>
          </Modal>
        );
      }
      render(<Nested />);
      fireEvent.click(screen.getByText('Delete'));
      expect(screen.getAllByRole('dialog')).toHaveLength(2);
      fireEvent.keyDown(window, { key: 'Escape' });
      expect(screen.getAllByRole('dialog')).toHaveLength(1);
      expect(screen.getByText('Delete')).toHaveFocus();
    });
  });
});
