import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Modal } from '../Modal';

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
});
