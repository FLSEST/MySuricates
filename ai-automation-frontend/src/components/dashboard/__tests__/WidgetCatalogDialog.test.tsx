import { describe, it, expect, beforeEach, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { useState } from 'react';
import { useI18nStore } from '../../../i18n';
import type { ResolvedWidget } from '../../../hooks/useDashboardLayout';
import type { DashboardWidgetDef, WidgetConfigFormProps } from '../widgetDefinition';
import { WidgetCatalogDialog } from '../WidgetCatalogDialog';

function NoteForm({ onSubmit, onCancel }: WidgetConfigFormProps) {
  const [text, setText] = useState('');
  return (
    <div>
      <input aria-label="Note text" value={text} onChange={(e) => setText(e.target.value)} />
      <button type="button" onClick={() => onSubmit({ text })}>Save note</button>
      <button type="button" onClick={onCancel}>Cancel note</button>
    </div>
  );
}

const Empty = () => null;
const def = (extra: Partial<DashboardWidgetDef> & Pick<DashboardWidgetDef, 'id'>): DashboardWidgetDef => ({
  label: () => extra.id, description: () => `About ${extra.id}`, category: 'overview', size: 'third', component: Empty, ...extra,
});

const runs = def({ id: 'runs', label: () => 'Runs', category: 'runs' });
const todos = def({ id: 'todos', label: () => 'Todos', category: 'planning' });
const gates = def({ id: 'gates', label: () => 'Quality Gates', category: 'quality', catalogOnly: true, description: () => '<b>not bold</b>' });
const note = def({ id: 'note', label: () => 'Note', multiple: true, configForm: NoteForm });
const link = def({ id: 'link', label: () => 'Link', multiple: true });
const resolved = (widget: DashboardWidgetDef, instanceId = widget.id): ResolvedWidget => ({ def: widget, instance: { instanceId, type: widget.id } });

function renderCatalog(props: Partial<Parameters<typeof WidgetCatalogDialog>[0]> = {}) {
  const onAdd = vi.fn();
  const onShow = vi.fn();
  render(
    <WidgetCatalogDialog
      open
      onClose={vi.fn()}
      available={[runs, todos, gates, note, link]}
      visible={[resolved(runs), resolved(note, 'note-1')]}
      hidden={[resolved(todos)]}
      canAppend
      disabled={false}
      onAdd={onAdd}
      onShow={onShow}
      {...props}
    />,
  );
  return { onAdd, onShow, dialog: screen.getByRole('dialog') };
}

beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
});

describe('WidgetCatalogDialog', () => {
  it('groups the widgets by category in a fixed order, with their descriptions as text', () => {
    const { dialog } = renderCatalog();
    expect(within(dialog).getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual([
      'Overview', 'Runs', 'Quality', 'Planning & todos',
    ]);
    expect(within(screen.getByRole('region', { name: 'Runs' })).getByText('About runs')).toBeInTheDocument();
    expect(within(dialog).getByText('<b>not bold</b>')).toBeInTheDocument();
    expect(dialog.querySelector('b')).toBeNull();
  });

  it('tells shown, hidden and addable widgets apart', () => {
    const { onAdd, onShow, dialog } = renderCatalog();

    expect(within(dialog).getByRole('button', { name: 'In view: Runs' })).toBeDisabled();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Show: Todos' }));
    expect(onShow).toHaveBeenCalledWith('todos');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add: Quality Gates' }));
    expect(onAdd).toHaveBeenCalledWith('gates');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add: Link' }));
    expect(onAdd).toHaveBeenCalledWith('link');
    expect(within(dialog).getByText('1 in this view')).toBeInTheDocument();
  });

  it('asks a multi widget for its config before adding it', () => {
    const { onAdd, dialog } = renderCatalog();

    fireEvent.click(within(dialog).getByRole('button', { name: 'Add: Note' }));
    expect(onAdd).not.toHaveBeenCalled();
    expect(within(dialog).getByRole('button', { name: 'Add: Note' })).toBeDisabled();

    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Note text' }), { target: { value: 'hello' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save note' }));

    expect(onAdd).toHaveBeenCalledWith('note', { text: 'hello' });
    expect(within(dialog).queryByRole('textbox', { name: 'Note text' })).not.toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole('button', { name: 'Add: Note' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel note' }));
    expect(within(dialog).queryByRole('textbox')).not.toBeInTheDocument();
    expect(onAdd).toHaveBeenCalledTimes(1);
  });

  it('shows hidden instances of a multi widget one by one', () => {
    const { onShow, dialog } = renderCatalog({
      visible: [resolved(link, 'link-1')],
      hidden: [resolved(link, 'link-2'), resolved(link, 'link-3')],
    });

    expect(within(dialog).getByText('3 in this view')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Show #2: Link' }));
    expect(onShow).toHaveBeenCalledWith('link-3');
    expect(within(dialog).getByRole('button', { name: 'Add: Link' })).toBeEnabled();
  });

  it('only shows hidden widgets again at the widget limit', () => {
    const { dialog } = renderCatalog({ canAppend: false });

    expect(within(dialog).getByRole('status')).toHaveTextContent('This view has reached 100 widgets.');
    expect(within(dialog).getByRole('button', { name: 'Show: Todos' })).toBeEnabled();
    expect(within(dialog).getByRole('button', { name: 'Add: Quality Gates' })).toBeDisabled();
    expect(within(dialog).getByRole('button', { name: 'Add: Note' })).toBeDisabled();
  });

  it('locks every action while disabled', () => {
    const { dialog } = renderCatalog({ disabled: true });
    for (const name of ['Show: Todos', 'Add: Quality Gates', 'Add: Note', 'Add: Link']) {
      expect(within(dialog).getByRole('button', { name })).toBeDisabled();
    }
  });
});
