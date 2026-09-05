package com.local.mcpserver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A minimal HTTP JSON-RPC server that implements the Model Context Protocol
 * (MCP) surface expected by clients: initialize, tools/list, and tools/call.
 *
 * It binds to a loopback address by default so that other local apps/agents on
 * the device can reach it, while not being exposed to the broader network.
 *
 * Protocol reference (streamable HTTP subset):
 *   POST <url>  {"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}
 *   POST <url>  {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
 *   POST <url>  {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"x","arguments":{...}}}
 * Each response is a JSON-RPC result object with a matching id.
 */
public class McpHttpServer {

    private final McpToolRegistry registry;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService pool;

    private ServerSocket serverSocket;
    private int port;
    private String jsonRpcId = "1"; // monotonically increasing request id

    public McpHttpServer(McpToolRegistry registry) {
        this.registry = registry;
        this.pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "mcp-http-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public int getPort() {
        return port;
    }

    public McpToolRegistry getRegistry() {
        return registry;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Start the server. Binds to 127.0.0.1 on the given port (0 => ephemeral).
     */
    public synchronized void start(int desiredPort) throws IOException {
        if (running.get()) {
            return;
        }
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", desiredPort));
        port = serverSocket.getLocalPort();
        running.set(true);
        Thread accept = new Thread(this::acceptLoop, "mcp-http-accept");
        accept.setDaemon(true);
        accept.start();
    }

    public synchronized void stop() {
        running.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                pool.submit(() -> handle(client));
            } catch (IOException e) {
                if (running.get()) {
                    // transient accept error, continue
                }
            }
        }
    }

    private void handle(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(15000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                return;
            }
            // Consume headers until blank line.
            String line;
            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            // Read the JSON-RPC body. JSON may be more than one line; read exactly contentLength bytes.
            char[] buf = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = reader.read(buf, read, contentLength - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            String body = new String(buf, 0, read);
            JSONObject response = process(body);
            String respBody = response.toString();
            byte[] out = respBody.getBytes(StandardCharsets.UTF_8);
            OutputStream os = s.getOutputStream();
            String header = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + out.length + "\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Access-Control-Allow-Headers: Content-Type, Authorization\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            os.write(header.getBytes(StandardCharsets.US_ASCII));
            os.write(out);
            os.flush();
        } catch (Exception e) {
            // client disconnect / parse error; drop silently
        }
    }

    private JSONObject process(String body) {
        JSONObject req;
        try {
            if (body == null || body.trim().isEmpty()) {
                return error(0, -32700, "Parse error: empty body");
            }
            req = new JSONObject(body);
        } catch (Exception e) {
            return error(0, -32700, "Parse error: " + e.getMessage());
        }
        Object idObj = req.opt("id");
        int id = (idObj instanceof Number) ? ((Number) idObj).intValue() : 0;
        String method = req.optString("method", "");
        JSONObject params = req.optJSONObject("params");
        if (params == null) {
            params = new JSONObject();
        }

        try {
            switch (method) {
                case "initialize":
                    return result(id, initializeResponse(params));
                case "notifications/initialized":
                    // Notification: no response expected, but we can ack with empty result.
                    return result(id, new JSONObject());
                case "ping":
                    return result(id, new JSONObject().put("ok", true));
                case "tools/list":
                    return result(id, toolsList());
                case "tools/call":
                    return result(id, toolsCall(params));
                case "prompts/list":
                    return result(id, new JSONObject().put("prompts", new JSONArray()));
                case "resources/list":
                    return result(id, new JSONObject().put("resources", new JSONArray()));
                default:
                    return error(id, -32601, "Method not found: " + method);
            }
        } catch (Exception e) {
            return error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private JSONObject initializeResponse(JSONObject params) {
        String proto = params.optString("protocolVersion", "2024-11-05");
        JSONObject caps = new JSONObject();
        caps.put("tools", new JSONObject());
        caps.put("prompts", new JSONObject());
        caps.put("resources", new JSONObject());
        JSONObject serverInfo = new JSONObject();
        serverInfo.put("name", "local-mcp-server");
        serverInfo.put("version", "1.0.0");
        JSONObject r = new JSONObject();
        r.put("protocolVersion", proto);
        r.put("capabilities", caps);
        r.put("serverInfo", serverInfo);
        return r;
    }

    private JSONObject toolsList() {
        List<McpTool> tools = registry.enabled();
        JSONArray arr = new JSONArray();
        for (McpTool t : tools) {
            arr.put(t.toListEntry());
        }
        return new JSONObject().put("tools", arr);
    }

    private JSONObject toolsCall(JSONObject params) throws Exception {
        String name = params.optString("name", "");
        JSONObject arguments = params.optJSONObject("arguments");
        if (arguments == null) {
            arguments = new JSONObject();
        }
        McpTool tool = registry.get(name);
        if (tool == null) {
            return new JSONObject().put("error", "unknown tool: " + name);
        }
        if (!tool.isEnabled()) {
            return new JSONObject().put("error", "tool disabled: " + name);
        }
        return tool.execute(arguments);
    }

    private JSONObject result(int id, JSONObject payload) {
        JSONObject r = new JSONObject();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        r.put("result", payload);
        return r;
    }

    private JSONObject error(int id, int code, String message) {
        JSONObject e = new JSONObject();
        e.put("code", code);
        e.put("message", message);
        JSONObject r = new JSONObject();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        r.put("error", e);
        return r;
    }
}
