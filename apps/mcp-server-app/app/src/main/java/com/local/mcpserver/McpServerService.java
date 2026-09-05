package com.local.mcpserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A foreground service that hosts the local MCP HTTP server. Starting the
 * service binds the server to 127.0.0.1:<port> and keeps it alive while the
 * app is in the foreground/background. The UI reads getPort()/isRunning() to
 * display the connection link.
 */
public class McpServerService extends Service {

    private static final String CHANNEL_ID = "mcp_server";
    private static final String TAG = "McpServerService";

    /** Default port the server tries to bind. */
    public static final int DEFAULT_PORT = 9090;

    /** Static handle so the UI can query state. */
    public static final AtomicReference<McpHttpServer> SERVER_REF = new AtomicReference<>();

    private McpHttpServer server;
    private Thread runThread;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForegroundCompat();
            startServer();
        } catch (Throwable t) {
            // Never crash on service start; log and do not restart.
            Log.e(TAG, "Failed to start MCP service", t);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void startServer() {
        if (server != null && server.isRunning()) {
            return;
        }
        McpToolRegistry registry = buildRegistry();
        server = new McpHttpServer(registry);
        SERVER_REF.set(server);
        runThread = new Thread(() -> {
            try {
                server.start(DEFAULT_PORT);
                Log.i(TAG, "MCP server listening on 127.0.0.1:" + server.getPort());
            } catch (Exception e) {
                Log.e(TAG, "Failed to start MCP server", e);
            }
        }, "mcp-server-start");
        runThread.start();
    }

    private McpToolRegistry buildRegistry() {
        Path workspace = getWorkspace();
        BuiltinTools bt = new BuiltinTools(workspace);
        return new McpToolRegistry(bt);
    }

    private Path getWorkspace() {
        File dir = new File(getFilesDir(), "workspace");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return Paths.get(dir.getAbsolutePath());
    }

    private void startForegroundCompat() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = "Local MCP Server";
        String text = "Listening on " + link();

        // Use the plain two-argument startForeground everywhere. With targetSdk 33
        // Android does not require a foreground-service type, so this avoids the
        // SecurityException that dataSync/specialUse type permissions can throw.
        startForeground(1001,
                new Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setContentIntent(pendingIntent)
                        .setOngoing(true)
                        .build());
    }

    private String link() {
        int p = server != null && server.getPort() > 0 ? server.getPort() : DEFAULT_PORT;
        return "http://127.0.0.1:" + p + "/mcp";
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "MCP Server", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Local MCP server foreground service");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
            server = null;
        }
        SERVER_REF.set(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
