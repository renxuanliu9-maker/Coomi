package com.local.mcpserver;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;

/**
 * Main configuration screen: shows the MCP connection link, a start/stop
 * toggle, the list of tools with enable/disable switches, and a connection
 * log viewer. It uses only android.app.Activity (no AndroidX to keep the
 * dependency tree free of kotlin-stdlib duplicate-class conflicts).
 */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 10;

    private TextView statusView;
    private TextView linkView;
    private TextView logView;
    private LinearLayout toolsLayout;
    private Button toggleButton;
    private Button refreshButton;
    private Button exportLogButton;
    private Button openPermButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        toggleButton.setOnClickListener(v -> toggleServer());
        refreshButton.setOnClickListener(v -> refresh());
        exportLogButton.setOnClickListener(v -> Toast.makeText(this, "日志已更新到 log view", Toast.LENGTH_SHORT).show());
        openPermButton.setOnClickListener(v -> requestStoragePermission());
    }

    private android.view.View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 40, 32, 32);
        root.setBackgroundColor(0xFF101216);

        TextView title = new TextView(this);
        title.setText("Local MCP Server");
        title.setTextSize(24);
        title.setTextColor(0xFFFFFFFF);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("提供本机 MCP 链接, 供其他智能体发现并调用内置工具。");
        sub.setTextColor(0xFFB0B6C0);
        sub.setTextSize(14);
        root.addView(sub);

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setTextColor(0xFF7CFC9A);
        root.addView(statusView);

        linkView = new TextView(this);
        linkView.setTextSize(15);
        linkView.setTextColor(0xFFFFD54F);
        linkView.setPadding(0, 8, 0, 8);
        root.addView(linkView);

        toggleButton = new Button(this);
        toggleButton.setText("启动服务器");
        root.addView(toggleButton);

        refreshButton = new Button(this);
        refreshButton.setText("刷新");
        root.addView(refreshButton);

        openPermButton = new Button(this);
        openPermButton.setText("请求存储权限");
        root.addView(openPermButton);

        TextView toolHeader = new TextView(this);
        toolHeader.setText("内置工具 (可开关)");
        toolHeader.setTextColor(0xFFFFFFFF);
        toolHeader.setTextSize(18);
        toolHeader.setPadding(0, 24, 0, 8);
        root.addView(toolHeader);

        ScrollView scroll = new ScrollView(this);
        toolsLayout = new LinearLayout(this);
        toolsLayout.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(toolsLayout);
        root.addView(scroll);

        TextView logHeader = new TextView(this);
        logHeader.setText("连接日志");
        logHeader.setTextColor(0xFFFFFFFF);
        logHeader.setTextSize(18);
        logHeader.setPadding(0, 24, 0, 8);
        root.addView(logHeader);

        logView = new TextView(this);
        logView.setTextColor(0xFF90CAF9);
        logView.setTextSize(12);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setPadding(0, 4, 0, 0);
        root.addView(logView);

        refresh();
        return root;
    }

    private void toggleServer() {
        Intent i = new Intent(this, McpServerService.class);
        if (McpServerService.SERVER_REF.get() != null && McpServerService.SERVER_REF.get().isRunning()) {
            stopService(i);
            appendLog("服务器已停止");
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            appendLog("正在启动服务器...");
        }
        refresh();
    }

    private void refresh() {
        McpHttpServer s = McpServerService.SERVER_REF.get();
        boolean running = s != null && s.isRunning();
        statusView.setText(running ? "● 运行中" : "○ 已停止");
        statusView.setTextColor(running ? 0xFF7CFC9A : 0xFFFF6B6B);
        int port = (s != null && s.getPort() > 0) ? s.getPort() : McpServerService.DEFAULT_PORT;
        linkView.setText("链接: http://127.0.0.1:" + port + "/mcp");
        toggleButton.setText(running ? "停止服务器" : "启动服务器");
        rebuildTools(s);
    }

    private void rebuildTools(McpHttpServer s) {
        toolsLayout.removeAllViews();
        List<McpTool> tools = (s != null)
                ? s.getRegistry().all()
                : defaultToolList();
        for (McpTool t : tools) {
            CheckBox cb = new CheckBox(this);
            cb.setText(t.name() + " — " + t.description());
            cb.setTextColor(0xFFE0E4EA);
            cb.setTextSize(13);
            cb.setChecked(t.isEnabled());
            cb.setOnCheckedChangeListener((v, checked) -> {
                if (s != null) {
                    s.getRegistry().setEnabled(t.name(), checked);
                    appendLog(t.name() + " 已" + (checked ? "启用" : "停用"));
                }
            });
            toolsLayout.addView(cb);
        }
    }

    private List<McpTool> defaultToolList() {
        // Fallback when server not running: show a static registry for the UI.
        McpToolRegistry reg = new McpToolRegistry(new BuiltinTools(
                Paths.get(getFilesDir().getAbsolutePath())));
        return reg.all();
    }

    private void appendLog(String line) {
        String cur = logView.getText().toString();
        String next = (cur.isEmpty() ? "" : cur + "\n") + java.time.LocalTime.now().toString().substring(0, 8) + " " + line;
        if (next.length() > 8000) {
            next = next.substring(next.length() - 8000);
        }
        logView.setText(next);
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            }
        } else {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE},
                        REQ_PERM);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            Toast.makeText(this, "权限请求已处理", Toast.LENGTH_SHORT).show();
        }
    }
}
