import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ConfirmModal } from '../ConfirmModal';
import { useI18nStore } from '../../../i18n';

describe('ConfirmModal', () => {
  beforeEach(() => {
    useI18nStore.setState({ locale: 'en' });
  });

  it('renders nothing while closed', () => {
    render(<ConfirmModal open={false} onClose={vi.fn()} onConfirm={vi.fn()} title="Delete?" confirmLabel="Delete" />);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows title, message and children', () => {
    render(
      <ConfirmModal open onClose={vi.fn()} onConfirm={vi.fn()} title="Delete x?" message="Gone for good" confirmLabel="Delete">
        <span>extra detail</span>
      </ConfirmModal>,
    );
    expect(screen.getByText('Delete x?')).toBeInTheDocument();
    expect(screen.getByText('Gone for good')).toBeInTheDocument();
    expect(screen.getByText('extra detail')).toBeInTheDocument();
  });

  it('calls onConfirm on the confirm button', () => {
    const onConfirm = vi.fn();
    const onClose = vi.fn();
    render(<ConfirmModal open onClose={onClose} onConfirm={onConfirm} title="T" confirmLabel="Archive" />);
    fireEvent.click(screen.getByRole('button', { name: 'Archive' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onClose).not.toHaveBeenCalled();
  });

  it('calls onClose on cancel with the default cancel label', () => {
    const onConfirm = vi.fn();
    const onClose = vi.fn();
    render(<ConfirmModal open onClose={onClose} onConfirm={onConfirm} title="T" confirmLabel="Archive" />);
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('closes on Escape like Modal', () => {
    const onClose = vi.fn();
    render(<ConfirmModal open onClose={onClose} onConfirm={vi.fn()} title="T" confirmLabel="OK" />);
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('locks the confirm button while pending', () => {
    const onConfirm = vi.fn();
    render(<ConfirmModal open onClose={vi.fn()} onConfirm={onConfirm} title="T" confirmLabel="Delete" isPending />);
    const confirm = screen.getByRole('button', { name: 'Delete' });
    expect(confirm).toBeDisabled();
    fireEvent.click(confirm);
    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeEnabled();
  });

  it('styles the confirm button as danger for destructive actions', () => {
    render(<ConfirmModal open onClose={vi.fn()} onConfirm={vi.fn()} title="T" confirmLabel="Delete" variant="danger" />);
    expect(screen.getByRole('button', { name: 'Delete' }).className).toMatch(/\bbg-danger\b/);
  });

  it('renders an extra action next to cancel and confirm', () => {
    const onExtra = vi.fn();
    render(
      <ConfirmModal
        open
        onClose={vi.fn()}
        onConfirm={vi.fn()}
        title="T"
        confirmLabel="Disconnect"
        extraAction={<button onClick={onExtra}>Delete credentials</button>}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Delete credentials' }));
    expect(onExtra).toHaveBeenCalledTimes(1);
  });
});
