package com.termos.app.vnc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.ImageButton;

import com.termos.R;
import com.iiordanov.bVNC.ConnectionBean;
import com.iiordanov.bVNC.Constants;
import com.iiordanov.bVNC.RemoteCanvas;
import com.iiordanov.bVNC.input.RemoteCanvasHandler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Manages VNC connection lifecycle for the OS tab.
 * Connects to localhost VNC server running in the Linux rootfs.
 */
public class VNCConnectionManager {

    private static final String TAG = "VNCConnectionManager";
    private static final String VNC_HOST = "127.0.0.1";
    private static final int DEFAULT_VNC_PORT = 5901; // Default port (display :1)
    private static final String VNC_DISPLAY_FILE = "/tmp/vnc-display.txt"; // File storing current display and port

    private final Context context;
    private RemoteCanvas canvas;
    private RemoteCanvasHandler handler;
    private boolean isConnected = false;
    private boolean isPaused = false;

    private ConnectionBean connection;
    private AlertDialog overlayDialog;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    public VNCConnectionManager(Context context, RemoteCanvas canvas) {
        this.context = context;
        this.canvas = canvas;
        initCanvas();
    }

    private void initCanvas() {
        handler = new RemoteCanvasHandler(canvas);
        canvas.setHandler(handler);
    }

    /**
     * Read the current VNC port from the saved file.
     * Returns default port if file doesn't exist or can't be read.
     */
    private int getVNCPort() {
        File file = new File(VNC_DISPLAY_FILE);
        if (!file.exists()) {
            return DEFAULT_VNC_PORT;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            if (line != null && line.contains(":")) {
                String[] parts = line.split(":");
                int display = Integer.parseInt(parts[1].trim());
                return 5900 + display;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read VNC display file", e);
        }

        return DEFAULT_VNC_PORT;
    }

    public void connect() {
        if (isConnected || isPaused) {
            return;
        }

        int port = getVNCPort();
        Log.i(TAG, "Connecting to VNC on port " + port);

        connection = new ConnectionBean();
        connection.setAddress(VNC_HOST);
        connection.setPort(port);
        connection.setColorModel(Constants.COLORMODEL_TRUECOLOR);
        connection.setForceFull(true);
        connection.setUseLocalCursor(true);
        connection.setViewOnly(false);

        try {
            canvas.initializeConnection(connection, null);
            isConnected = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize VNC connection", e);
            isConnected = false;
        }
    }

    public void disconnect() {
        try {
            if (canvas != null) {
                canvas.shutdown();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error while shutting down VNC canvas", e);
        }
        isConnected = false;
    }

    public void pause() {
        isPaused = true;
        disconnect();
    }

    public void resume() {
        isPaused = false;
        connect();
    }

    public boolean isConnected() {
        return isConnected && !isPaused;
    }

    public void showOverlay(Activity activity) {
        if (overlayDialog != null && overlayDialog.isShowing()) {
            return;
        }

        View view = LayoutInflater.from(activity)
                .inflate(R.layout.vnc_overlay_controls, null);

        ImageButton closeBtn = view.findViewById(R.id.btn_close);
        ImageButton reconnectBtn = view.findViewById(R.id.btn_reconnect);

        closeBtn.setOnClickListener(v -> hideOverlay());
        reconnectBtn.setOnClickListener(v -> {
            disconnect();
            connect();
        });

        overlayDialog = new AlertDialog.Builder(activity)
                .setView(view)
                .create();

        overlayDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        overlayDialog.show();
    }

    public void hideOverlay() {
        if (overlayDialog != null) {
            overlayDialog.dismiss();
            overlayDialog = null;
        }
    }

    /* =====================================================================
     * ✅ ADDED FOR VNC AUTO-RESTART SUPPORT (SAFE, NON-INTRUSIVE)
     * ===================================================================== */

    /**
     * Used by OS tab polling logic to detect a dead VNC session.
     * Does NOT restart VNC itself — Linux watchdog handles that.
     */
    public boolean isSessionAlive() {
        try {
            return isConnected && !isPaused;
        } catch (Exception e) {
            return false;
        }
    }
}
