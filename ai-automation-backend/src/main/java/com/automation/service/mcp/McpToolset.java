package com.automation.service.mcp;

import java.util.List;

/** A group of MCP tools; {@link McpServer} collects every bean of this type. */
public interface McpToolset {

    List<McpTool> tools();
}
