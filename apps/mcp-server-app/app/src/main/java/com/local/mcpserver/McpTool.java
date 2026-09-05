package com.local.mcpserver;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A single MCP tool exposed to remote agents. Each tool declares its name,
 * description, and input schema (JSON Schema), plus a handler that runs the
 * tool with the provided arguments and returns a text result.
 */
public interface McpTool {

    /** Canonical tool name used in tools/list and tools/call. */
    String name();

    /** Human-readable description shown to the agent. */
    String description();

    /** JSON Schema object describing accepted arguments. */
    JSONObject inputSchema();

    /**
     * Execute the tool with the given arguments.
     *
     * @param arguments parsed JSON object of tool arguments (never null)
     * @return a JSON-encodable result object (e.g. {"content":[...]})
     * @throws Exception if the tool fails; the caller wraps it as an MCP error
     */
    JSONObject execute(JSONObject arguments) throws Exception;

    /** True if this tool is currently enabled (not disabled by the UI). */
    boolean isEnabled();

    /** Enable or disable this tool at runtime. */
    void setEnabled(boolean enabled);

    /** Build the tool object entry for tools/list. */
    default JSONObject toListEntry() {
        JSONObject o = new JSONObject();
        o.put("name", name());
        o.put("description", description());
        o.put("inputSchema", inputSchema());
        return o;
    }
}
