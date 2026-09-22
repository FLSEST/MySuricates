import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Modal } from '../Modal';
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
});
