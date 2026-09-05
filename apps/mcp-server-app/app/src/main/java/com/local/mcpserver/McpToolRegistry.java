package com.local.mcpserver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of enabled tools exposed to the remote agent. Provides a stable
 * ordered listing for tools/list and dispatch for tools/call.
 */
public class McpToolRegistry {

    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    private static JSONObject schema(String... required) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "object");
            JSONObject props = new JSONObject();
            for (int i = 0; i < required.length; i += 2) {
                JSONObject p = new JSONObject();
                p.put("type", required[i + 1]);
                props.put(required[i], p);
            }
            obj.put("properties", props);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < required.length; i += 2) {
                arr.put(required[i]);
            }
            obj.put("required", arr);
            obj.put("additionalProperties", false);
            return obj;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public McpToolRegistry(BuiltinTools bt) {
        tools.put("list_dir", new SimpleTool("list_dir",
                "List files and directories under the workspace path.",
                schema("path", "string"), bt::listDir));
        tools.put("read_file", new SimpleTool("read_file",
                "Read a UTF-8 text file with line numbers. Only inside the app workspace.",
                schema("path", "string"), bt::readFile));
        tools.put("write_file", new SimpleTool("write_file",
                "Create or replace a UTF-8 text file inside the app workspace.",
                schema("path", "string", "content", "string"), bt::writeFile));
        tools.put("echo", new SimpleTool("echo",
                "Echo back the provided text and server info. Use as a round-trip health check.",
                schema("text", "string"), bt::echo));
        tools.put("web_search", new SimpleTool("web_search",
                "Search the web and return ranked text results for the query.",
                schema("query", "string"), bt::webSearch));
        tools.put("fetch", new SimpleTool("fetch",
                "Fetch a web page over HTTP(S) and return its readable text content.",
                schema("url", "string"), bt::fetch));
        tools.put("shell", new SimpleTool("shell",
                "Run a restricted read-only shell command (echo/ls/pwd/date/uname) inside the app.",
                schema("command", "string"), bt::shell));
    }

    public List<McpTool> all() {
        return new ArrayList<>(tools.values());
    }

    public List<McpTool> enabled() {
        List<McpTool> out = new ArrayList<>();
        for (McpTool t : tools.values()) {
            if (t.isEnabled()) {
                out.add(t);
            }
        }
        return out;
    }

    public McpTool get(String name) {
        return tools.get(name);
    }

    public boolean setEnabled(String name, boolean enabled) {
        McpTool t = tools.get(name);
        if (t == null) {
            return false;
        }
        t.setEnabled(enabled);
        return true;
    }
}
