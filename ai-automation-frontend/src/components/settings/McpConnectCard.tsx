import { useState } from 'react';
import { Eye, EyeOff, Info, KeyRound, ShieldAlert } from 'lucide-react';
import { Card, Button } from '../ui';
import { ConfigSnippet } from './ConfigSnippet';
import { useSettingsStore } from '../../stores/settingsStore';
import { useT } from '../../i18n';
import { MCP_KEY_PLACEHOLDER, claudeMcpAddCommand, mcpEndpointUrl, mcpServersJson } from '../../utils/mcpSnippets';

const MASK = '••••••••';

/**
 * How an AI client connects to this instance through the MCP server (module `mcp`). The snippets
 * carry the API key this browser sends — masked on screen until revealed; copying takes the real value.
 */
export function McpConnectCard() {
  const t = useT();
  const apiKey = useSettingsStore((s) => s.apiKey).trim();
  const [revealed, setRevealed] = useState(false);
  const url = mcpEndpointUrl(window.location.origin);
  const hasKey = apiKey.length > 0;
  const realKey = hasKey ? apiKey : MCP_KEY_PLACEHOLDER;
  const shownKey = hasKey && !revealed ? MASK : realKey;

  return (
    <Card>
      {/* The settings section already carries the title */}
      <div className="space-y-4" data-testid="mcp-connect">
        <div className="flex flex-wrap items-start gap-3">
          <p className="min-w-0 flex-1 text-sm text-text-secondary">{t.mcpConnect.intro}</p>
          {hasKey && (
            <Button
              size="sm"
              variant="ghost"
              icon={revealed ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
              onClick={() => setRevealed((v) => !v)}
              aria-pressed={revealed}
            >
              {revealed ? t.mcpConnect.hideKey : t.mcpConnect.showKey}
            </Button>
          )}
        </div>
        <ConfigSnippet label={t.mcpConnect.endpoint} text={url} />
        <ConfigSnippet
          label={t.mcpConnect.claudeCode}
          text={claudeMcpAddCommand(url, shownKey)}
          copyText={claudeMcpAddCommand(url, realKey)}
        />
        <div className="space-y-1.5">
          <ConfigSnippet
            label={t.mcpConnect.configFile}
            text={mcpServersJson(url, shownKey)}
            copyText={mcpServersJson(url, realKey)}
          />
          <p className="text-xs text-text-tertiary">{t.mcpConnect.vscodeNote}</p>
        </div>
        {!hasKey && (
          <p className="flex items-start gap-2 text-xs text-text-secondary" data-testid="mcp-key-missing">
            <KeyRound className="w-3.5 h-3.5 mt-0.5 shrink-0" />
            {t.mcpConnect.keyMissing}
          </p>
        )}
        <p className="flex items-start gap-2 text-xs text-warning">
          <ShieldAlert className="w-3.5 h-3.5 mt-0.5 shrink-0" />
          {t.mcpConnect.dataWarning}
        </p>
        <p className="flex items-start gap-2 text-xs text-text-tertiary">
          <Info className="w-3.5 h-3.5 mt-0.5 shrink-0" />
          {t.mcpConnect.userMcpNote}
        </p>
      </div>
    </Card>
  );
}
