package com.termos.app.vnc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.FrameLayout;

import com.iiordanov.bVNC.ConnectionBean;
import com.iiordanov.bVNC.RemoteCanvas;

/**
 * Enhanced VNC viewer using bVNC library with improved connection handling
 * Uses the existing bVNC library that comes with Termos for better compatibility
 */
public class SimpleVNCViewer extends FrameLayout {
    private static final String TAG = "SimpleVNCViewer";

    private RemoteCanvas vncCanvas;
    private ConnectionBean connection;
    private VNCListener listener;
    private Handler uiHandler;

    public interface VNCListener {
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    public SimpleVNCViewer(Context context) {
        super(context);
        init();
    }

    public SimpleVNCViewer(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        uiHandler = new Handler(Looper.getMainLooper());
        setFocusable(true);
        setFocusableInTouchMode(true);

        // Initialize bVNC components
        initializeBVNC();
    }

    private void initializeBVNC() {
        try {
            // Create connection bean
            connection = new ConnectionBean(getContext());
            connection.setNickname("Termos VNC");
            connection.setConnectionType(com.iiordanov.bVNC.Constants.CONN_TYPE_PLAIN);

            // Create VNC canvas
            vncCanvas = new RemoteCanvas(getContext(), null);

            // Add canvas to layout
            addView(vncCanvas, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            Log.d(TAG, "bVNC components initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize bVNC components", e);
        }
    }

    public void setVNCListener(VNCListener listener) {
        this.listener = listener;
    }

    public void connect(String host, int port, String password) {
        if (connection == null || vncCanvas == null) {
            Log.e(TAG, "bVNC not initialized");
            if (listener != null) {
                listener.onError("VNC viewer not initialized");
            }
            return;
        }

        disconnect();

        try {
            Log.d(TAG, "Connecting to VNC server using bVNC: " + host + ":" + port);

            // Configure connection
            connection.setAddress(host);
            connection.setPort(port);
            connection.setPassword(password != null ? password : "");
            connection.setKeepPassword(true);

            // Set up canvas with connection
            vncCanvas.initializeVncCanvas(connection, getContext());

            // Start connection
            vncCanvas.startVncConnection();

            Log.d(TAG, "bVNC connection initiated");

            // Monitor connection status
            monitorConnection();

        } catch (Exception e) {
            Log.e(TAG, "Failed to start bVNC connection", e);
            if (listener != null) {
                listener.onError("Failed to start VNC connection: " + e.getMessage());
            }
        }
    }

    private void monitorConnection() {
        // Check connection status after a delay
        uiHandler.postDelayed(() -> {
            try {
                if (vncCanvas != null && vncCanvas.isConnectionActive()) {
                    Log.d(TAG, "bVNC connection established");
                    if (listener != null) {
                        listener.onConnected();
                    }
                } else {
                    Log.w(TAG, "bVNC connection failed or not active");
                    if (listener != null) {
                        listener.onError("VNC connection failed. Try using RVNC Viewer or RealVNC Viewer apps.");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking connection status", e);
                if (listener != null) {
                    listener.onError("Connection status check failed");
                }
            }
        }, 3000); // Check after 3 seconds
    }

    public void disconnect() {
        try {
            if (vncCanvas != null) {
                vncCanvas.closeConnection();
                Log.d(TAG, "bVNC connection closed");
            }

            if (listener != null) {
                listener.onDisconnected();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error disconnecting bVNC", e);
        }
    }

    public boolean isConnected() {
        try {
            return vncCanvas != null && vncCanvas.isConnectionActive();
        } catch (Exception e) {
            return false;
        }
    }
}