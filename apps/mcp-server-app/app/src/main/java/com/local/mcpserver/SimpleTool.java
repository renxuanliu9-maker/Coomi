package com.local.mcpserver;

import org.json.JSONObject;

/**
 * A concrete McpTool backed by a ToolExecutor lambda and an enable flag.
 */
public class SimpleTool implements McpTool {

    private final String name;
    private final String description;
    private final JSONObject inputSchema;
    private final ToolExecutor executor;
    private volatile boolean enabled = true;

    public SimpleTool(String name, String description, JSONObject inputSchema, ToolExecutor executor) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.executor = executor;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public JSONObject inputSchema() {
        return inputSchema;
    }

    @Override
    public JSONObject execute(JSONObject arguments) throws Exception {
        return executor.execute(arguments);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
