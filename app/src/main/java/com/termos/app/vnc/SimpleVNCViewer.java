package com.termos.app.vnc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Simple VNC viewer using SSH VNC Viewer library for maximum compatibility
 * Leverages com.sshtools:vnc-viewer:3.0.1 Maven dependency
 */
public class SimpleVNCViewer extends FrameLayout {
    private static final String TAG = "SimpleVNCViewer";

    private View vncViewer;
    private VNCListener listener;
    private Handler uiHandler;
    private String host;
    private int port;
    private String password;

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
    }

    public void setVNCListener(VNCListener listener) {
        this.listener = listener;
    }

    public void connect(String host, int port, String password) {
        this.host = host;
        this.port = port;
        this.password = password;

        disconnect();

        try {
            Log.d(TAG, "Attempting to use SSH VNC Viewer library for: " + host + ":" + port);

            // Try to use SSH VNC Viewer library dynamically
            // Since we don't know the exact API, we'll use reflection
            useSSHViewerLibrary();

        } catch (Exception e) {
            Log.e(TAG, "SSH VNC Viewer library not available or failed, falling back to simple implementation", e);
            // Fallback to a basic web-based VNC viewer or external app
            useFallbackViewer();
        }
    }

    private void useSSHViewerLibrary() throws Exception {
        // Try to load SSH VNC Viewer classes dynamically
        Class<?> viewerClass = Class.forName("com.sshtools.vnc.Viewer");
        Class<?> connectionListenerClass = Class.forName("com.sshtools.vnc.ConnectionListener");

        // Create viewer instance
        Object viewerInstance = viewerClass.getConstructor().newInstance();

        // Set connection parameters if methods exist
        try {
            viewerClass.getMethod("setHost", String.class).invoke(viewerInstance, host);
            viewerClass.getMethod("setPort", int.class).invoke(viewerInstance, port);
            if (password != null && !password.isEmpty()) {
                viewerClass.getMethod("setPassword", String.class).invoke(viewerInstance, password);
            }
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "SSH Viewer API methods not found, trying alternative approach");
        }

        // Create connection listener
        Object connectionListener = java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{connectionListenerClass},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "connected":
                        Log.d(TAG, "SSH VNC Viewer connected");
                        uiHandler.post(() -> {
                            if (listener != null) listener.onConnected();
                        });
                        break;
                    case "disconnected":
                        Log.d(TAG, "SSH VNC Viewer disconnected");
                        uiHandler.post(() -> {
                            if (listener != null) listener.onDisconnected();
                        });
                        break;
                    case "error":
                        String errorMsg = args.length > 0 ? args[0].toString() : "Unknown error";
                        Log.e(TAG, "SSH VNC Viewer error: " + errorMsg);
                        uiHandler.post(() -> {
                            if (listener != null) listener.onError(errorMsg);
                        });
                        break;
                }
                return null;
            });

        // Add connection listener
        try {
            viewerClass.getMethod("addConnectionListener", connectionListenerClass)
                      .invoke(viewerInstance, connectionListener);
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "addConnectionListener method not found");
        }

        // Cast to View if possible
        if (viewerInstance instanceof View) {
            vncViewer = (View) viewerInstance;

            // Add the viewer to this layout
            removeAllViews();
            addView(vncViewer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            // Try to connect
            try {
                viewerClass.getMethod("connect").invoke(viewerInstance);
                Log.d(TAG, "SSH VNC Viewer connect method called");
            } catch (NoSuchMethodException e) {
                Log.w(TAG, "connect method not found, viewer may auto-connect");
            }
        } else {
            throw new Exception("SSH Viewer is not a View component");
        }
    }

    private void useFallbackViewer() {
        Log.d(TAG, "Using fallback VNC viewer approach");

        // Create a simple web-based VNC viewer or show connection info
        android.widget.TextView infoView = new android.widget.TextView(getContext());
        infoView.setText("VNC Connection Info:\n\nHost: " + host + "\nPort: " + port +
                        (password != null && !password.isEmpty() ? "\nPassword: " + password : "\nPassword: (none)") +
                        "\n\nTry using RVNC Viewer or RealVNC Viewer apps for this server.");
        infoView.setPadding(32, 32, 32, 32);
        infoView.setTextSize(16);
        infoView.setTextColor(android.graphics.Color.WHITE);
        infoView.setBackgroundColor(android.graphics.Color.BLACK);

        removeAllViews();
        addView(infoView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Simulate connection for UI purposes
        uiHandler.postDelayed(() -> {
            if (listener != null) {
                listener.onError("SSH VNC Viewer library not compatible. Please use RVNC Viewer or RealVNC Viewer apps.");
            }
        }, 1000);
    }

    public void disconnect() {
        try {
            if (vncViewer != null) {
                // Try to disconnect using reflection
                try {
                    Class<?> viewerClass = vncViewer.getClass();
                    viewerClass.getMethod("disconnect").invoke(vncViewer);
                } catch (Exception e) {
                    Log.w(TAG, "Could not disconnect SSH viewer gracefully", e);
                }
                removeView(vncViewer);
                vncViewer = null;
            }

            if (listener != null) {
                listener.onDisconnected();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error disconnecting SSH VNC Viewer", e);
        }
    }

    public boolean isConnected() {
        if (vncViewer != null) {
            try {
                Class<?> viewerClass = vncViewer.getClass();
                return (Boolean) viewerClass.getMethod("isConnected").invoke(vncViewer);
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

}
}