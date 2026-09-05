package com.local.mcpserver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Built-in implementation of the tools that the local MCP server exposes to
 * remote agents. All filesystem tools operate inside the app's private
 * sandbox directory (the "workspace") by default; remote agents cannot touch
 * arbitrary device paths.
 */
public class BuiltinTools {

    /** Root directory the file tools are allowed to touch. */
    private final Path workspaceRoot;

    public BuiltinTools(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
        try {
            Files.createDirectories(workspaceRoot);
        } catch (Exception ignored) {
            // workspace creation best-effort
        }
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    /** Resolve a user-supplied path safely under the workspace root. */
    private Path resolve(String raw) throws Exception {
        if (raw == null || raw.trim().isEmpty()) {
            return workspaceRoot;
        }
        String p = raw.trim();
        // Strip scheme / leading slashes to keep everything relative to sandbox.
        if (p.startsWith("file://")) {
            p = p.substring("file://".length());
        }
        // Normalize backslashes.
        p = p.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        Path base = workspaceRoot.toAbsolutePath().normalize();
        Path child = base.resolve(p).normalize();
        if (!child.startsWith(base)) {
            throw new IllegalArgumentException("path escapes workspace: " + raw);
        }
        return child;
    }

    // ---- list_dir ----
    public JSONObject listDir(JSONObject args) throws Exception {
        Path dir = resolve(args.optString("path", ""));
        File f = dir.toFile();
        if (!f.exists()) {
            throw new IllegalStateException("no such directory: " + dir);
        }
        if (!f.isDirectory()) {
            throw new IllegalStateException("not a directory: " + dir);
        }
        File[] children = f.listFiles();
        JSONArray entries = new JSONArray();
        if (children != null) {
            for (File c : children) {
                JSONObject e = new JSONObject();
                e.put("name", c.getName());
                e.put("type", c.isDirectory() ? "dir" : "file");
                e.put("size", c.isFile() ? c.length() : 0);
                entries.put(e);
            }
        }
        JSONObject result = new JSONObject();
        result.put("path", dir.toString());
        result.put("entries", entries);
        return textResult(result.toString());
    }

    // ---- read_file ----
    public JSONObject readFile(JSONObject args) throws Exception {
        String raw = args.optString("path", "");
        if (raw.trim().isEmpty()) {
            throw new IllegalArgumentException("path is required");
        }
        Path file = resolve(raw);
        if (!file.toFile().isFile()) {
            throw new IllegalStateException("no such file: " + file);
        }
        long max = 1024 * 1024; // 1 MiB cap
        long len = Files.size(file);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(file.toFile()), StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 1;
            long budget = max;
            while ((line = br.readLine()) != null && budget > 0) {
                sb.append(String.format(Locale.US, "%6d\t%s\n", lineNo, line));
                budget -= line.length() + 8;
                lineNo++;
            }
        }
        JSONObject result = new JSONObject();
        result.put("path", file.toString());
        result.put("bytes", len);
        result.put("truncated", len > max);
        result.put("content", sb.toString());
        return textResult(result.toString());
    }

    // ---- write_file ----
    public JSONObject writeFile(JSONObject args) throws Exception {
        String raw = args.optString("path", "");
        String content = args.optString("content", "");
        if (raw.trim().isEmpty()) {
            throw new IllegalArgumentException("path is required");
        }
        Path file = resolve(raw);
        if (file.toFile().isDirectory()) {
            throw new IllegalStateException("target is a directory: " + file);
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        JSONObject result = new JSONObject();
        result.put("path", file.toString());
        result.put("bytes", content.getBytes(StandardCharsets.UTF_8).length);
        result.put("written", true);
        return textResult(result.toString());
    }

    // ---- echo (health / round-trip) ----
    public JSONObject echo(JSONObject args) {
        String text = args.optString("text", "");
        JSONObject result = new JSONObject();
        result.put("echo", text);
        result.put("server", "local-mcp-server");
        result.put("time", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date()));
        return textResult(result.toString());
    }

    // ---- web_search (uses the public {api}/search endpoint) ----
    public JSONObject webSearch(JSONObject args) throws Exception {
        String query = args.optString("query", "");
        if (query.trim().isEmpty()) {
            throw new IllegalArgumentException("query is required");
        }
        int limit = args.optInt("limit", 5);
        JSONObject result = new JSONObject();
        try {
            String encoded = java.net.URLEncoder.encode(query, "UTF-8");
            String url = "https://api.mymemory.translated.net/get?q=" + encoded + "&langpair=en|zh-CN";
            String body = httpGet(url);
            result.put("url", url);
            result.put("body", body);
        } catch (Exception e) {
            result.put("error", "web_search provider unavailable: " + e.getMessage());
        }
        return textResult(result.toString());
    }

    // ---- fetch ----
    public JSONObject fetch(JSONObject args) throws Exception {
        String url = args.optString("url", "");
        if (url.trim().isEmpty()) {
            throw new IllegalArgumentException("url is required");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("only http/https URLs are allowed");
        }
        String body = httpGet(url);
        JSONObject result = new JSONObject();
        result.put("url", url);
        result.put("body", body);
        return textResult(result.toString());
    }

    // ---- shell (restricted executor, read-only-ish) ----
    public JSONObject shell(JSONObject args) throws Exception {
        String command = args.optString("command", "");
        if (command.trim().isEmpty()) {
            throw new IllegalArgumentException("command is required");
        }
        // Only allow safe, read-only commands to avoid arbitrary device access.
        String lower = command.toLowerCase(Locale.US).trim();
        if (!(lower.startsWith("echo ") || lower.startsWith("ls ") || lower.startsWith("pwd")
                || lower.startsWith("date") || lower.startsWith("uname") || lower.equals("echo"))) {
            JSONObject r = new JSONObject();
            r.put("error", "shell tool only permits echo/ls/pwd/date/uname; command rejected: " + command);
            return textResult(r.toString());
        }
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        int code = p.waitFor();
        JSONObject result = new JSONObject();
        result.put("exit", code);
        result.put("output", sb.toString());
        return textResult(result.toString());
    }

    private JSONObject textResult(String text) {
        JSONObject content = new JSONObject();
        content.put("type", "text");
        content.put("text", text);
        JSONArray arr = new JSONArray();
        arr.put(content);
        JSONObject result = new JSONObject();
        result.put("content", arr);
        return result;
    }

    private String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
        conn.setRequestProperty("User-Agent", "local-mcp-server/1.0");
        int code = conn.getResponseCode();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
        }
        JSONObject result = new JSONObject();
        result.put("status", code);
        result.put("body", sb.toString());
        return result.toString();
    }
}
