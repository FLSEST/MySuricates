/** Stands in for the API key when this browser has none stored. */
export const MCP_KEY_PLACEHOLDER = '<API-KEY>';

/** Endpoint of the MCP server on the UI's origin — in dev the Vite proxy forwards /api to the backend. */
export function mcpEndpointUrl(origin: string): string {
  return `${origin}/api/v1/mcp`;
}

/** Command that registers this instance in Claude Code (Streamable HTTP transport). */
export function claudeMcpAddCommand(url: string, key: string): string {
  return `claude mcp add --transport http mysuricates ${url} --header "X-API-Key: ${key}"`;
}

/** Generic `mcpServers` entry for clients configured by file (Cursor; VS Code names the key `servers`). */
export function mcpServersJson(url: string, key: string): string {
  return JSON.stringify({ mcpServers: { mysuricates: { url, headers: { 'X-API-Key': key } } } }, null, 2);
}
