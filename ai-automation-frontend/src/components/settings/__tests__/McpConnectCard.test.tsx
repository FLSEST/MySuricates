import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { useSettingsStore } from '../../../stores/settingsStore';
import { McpConnectCard } from '../McpConnectCard';
import { claudeMcpAddCommand, mcpServersJson } from '../../../utils/mcpSnippets';

const KEY = 'secret-key-1234';
const writeText = vi.fn().mockResolvedValue(undefined);

beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  writeText.mockClear();
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
});
afterEach(() => {
  useSettingsStore.setState({ apiKey: '' });
});

const url = `${window.location.origin}/api/v1/mcp`;

describe('McpConnectCard', () => {
  it('shows the endpoint and the Claude Code command with the key masked, and copies the real key', async () => {
    useSettingsStore.setState({ apiKey: KEY });
    renderWithProviders(<McpConnectCard />);

    const card = screen.getByTestId('mcp-connect');
    expect(within(card).getByText(url)).toBeInTheDocument();
    expect(within(card).getByText(claudeMcpAddCommand(url, '••••••••'))).toBeInTheDocument();
    expect(card).not.toHaveTextContent(KEY);
    expect(screen.queryByTestId('mcp-key-missing')).not.toBeInTheDocument();

    // Second copy button belongs to the Claude Code command
    fireEvent.click(within(card).getAllByRole('button', { name: 'Copy' })[1]);
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(claudeMcpAddCommand(url, KEY)));
    fireEvent.click(within(card).getAllByRole('button', { name: 'Copy' })[2]);
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(mcpServersJson(url, KEY)));
  });

  it('reveals and hides the key on request', () => {
    useSettingsStore.setState({ apiKey: KEY });
    renderWithProviders(<McpConnectCard />);

    fireEvent.click(screen.getByRole('button', { name: 'Show API key' }));
    expect(screen.getByText(claudeMcpAddCommand(url, KEY))).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Hide API key' }));
    expect(screen.getByTestId('mcp-connect')).not.toHaveTextContent(KEY);
  });

  it('uses a placeholder and says where the key is when this browser has none', () => {
    useSettingsStore.setState({ apiKey: '' });
    renderWithProviders(<McpConnectCard />);

    expect(screen.getByText(claudeMcpAddCommand(url, '<API-KEY>'))).toBeInTheDocument();
    expect(screen.getByTestId('mcp-key-missing')).toHaveTextContent('~/.mysuricates/api-key');
    expect(screen.queryByRole('button', { name: 'Show API key' })).not.toBeInTheDocument();
  });

  it('warns that tool results go to the client provider and names the user_mcp_servers opt-in', () => {
    renderWithProviders(<McpConnectCard />);

    expect(screen.getByText(/go to the AI provider of the client/)).toBeInTheDocument();
    expect(screen.getByText(/"user_mcp_servers": true/)).toBeInTheDocument();
    expect(screen.getByText(/names the top-level key "servers"/)).toBeInTheDocument();
  });
});

describe('mcpSnippets', () => {
  it('builds the generic mcpServers entry', () => {
    expect(JSON.parse(mcpServersJson('http://h/api/v1/mcp', 'k'))).toEqual({
      mcpServers: { mysuricates: { url: 'http://h/api/v1/mcp', headers: { 'X-API-Key': 'k' } } },
    });
  });

  it('builds the claude mcp add command', () => {
    expect(claudeMcpAddCommand('http://h/api/v1/mcp', 'k'))
      .toBe('claude mcp add --transport http mysuricates http://h/api/v1/mcp --header "X-API-Key: k"');
  });
});
