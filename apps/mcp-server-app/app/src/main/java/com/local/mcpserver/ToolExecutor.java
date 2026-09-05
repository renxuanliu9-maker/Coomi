package com.local.mcpserver;

import org.json.JSONObject;

/**
 * Functional handler for a tool body. Used by SimpleTool to wrap the built-in
 * tool methods as McpTool instances.
 */
@FunctionalInterface
public interface ToolExecutor {
    JSONObject execute(JSONObject arguments) throws Exception;
}
