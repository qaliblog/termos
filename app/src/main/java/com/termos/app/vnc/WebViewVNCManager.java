package com.termos.app.vnc;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.termos.app.linuxruntime.LinuxCommandExecutor;
import com.termux.terminal.TerminalSessionClient;

/**
 * Simple internal VNC viewer using WebView and HTML5 canvas.
 * Much more reliable than the complex bVNC library.
 */
public class WebViewVNCManager {
    private static final String TAG = "WebViewVNCManager";

    private Context context;
    private Activity activity;
    private WebView webView;
    private Handler uiHandler;
    private LinuxCommandExecutor commandExecutor;
    private TerminalSessionClient serviceClient;

    // Connection state
    private boolean isConnected = false;
    private String currentHost;
    private int currentPort;
    private String currentPassword;

    // Status callback interface
    public interface VNCStatusCallback {
        void onVncConnected();
        void onVncConnectionFailed(String error);
        void onVncDisconnected();
    }

    private VNCStatusCallback statusCallback;

    public WebViewVNCManager(Context context, Activity activity) {
        this.context = context;
        this.activity = activity;
        this.uiHandler = new Handler(Looper.getMainLooper());
        this.commandExecutor = new LinuxCommandExecutor(context);
    }

    /**
     * Initialize with a WebView widget
     */
    public void initialize(WebView webView) {
        this.webView = webView;

        // Configure WebView settings
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        // Set up WebView clients
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "WebView page loaded: " + url);
                // Inject JavaScript interface for status updates
                injectJavaScriptInterface();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.e(TAG, "WebView error: " + errorCode + " - " + description);
                if (statusCallback != null) {
                    statusCallback.onVncConnectionFailed("WebView error: " + description);
                }
            }
        });

        // Add JavaScript interface for communication
        webView.addJavascriptInterface(this, "Android");

        // Load the VNC viewer HTML
        webView.loadUrl("file:///android_asset/novnc/index.html");
    }

    /**
     * Inject JavaScript interface for communication with WebView
     */
    private void injectJavaScriptInterface() {
        webView.evaluateJavascript(
            "javascript:" +
            "window.AndroidVNC = {" +
            "   onConnected: function() { if(typeof Android !== 'undefined') Android.onVncConnected(); }," +
            "   onConnectionFailed: function(error) { if(typeof Android !== 'undefined') Android.onVncConnectionFailed(error); }," +
            "   onDisconnected: function() { if(typeof Android !== 'undefined') Android.onVncDisconnected(); }" +
            "};" +
            // Also set up a simple status indicator
            "console.log('Termos VNC Viewer loaded for ' + window.location.search);",
            null
        );
    }

    /**
     * Set the terminal session client for command execution
     */
    public void setServiceClient(TerminalSessionClient serviceClient) {
        this.serviceClient = serviceClient;
    }

    /**
     * Set status callback
     */
    public void setStatusCallback(VNCStatusCallback callback) {
        this.statusCallback = callback;
    }

    /**
     * Connect to VNC server
     */
    public void connect(String host, int port, String username, String password) {
        this.currentHost = host;
        this.currentPort = port;
        this.currentPassword = password;

        Log.d(TAG, "Connecting to VNC: " + host + ":" + port + " as user: " + (username != null ? username : "(none)"));

        // Check if VNC server is running first
        if (serviceClient != null) {
            commandExecutor.checkVNCServerRunning(serviceClient, new LinuxCommandExecutor.ServerCheckCallback() {
                @Override
                public void onResult(boolean isRunning) {
                    if (isRunning) {
                        Log.d(TAG, "VNC server is running, proceeding with connection");
                        loadVNCViewer();
                    } else {
                        Log.w(TAG, "VNC server not detected as running");
                        // Still try to connect in case our detection is wrong
                        loadVNCViewer();
                    }
                }
            });
        } else {
            // No service client, just load the viewer
            loadVNCViewer();
        }
    }

    private void loadVNCViewer() {
        // Build connection URL with parameters
        StringBuilder urlBuilder = new StringBuilder("file:///android_asset/novnc/index.html?");
        urlBuilder.append("host=").append(currentHost);
        urlBuilder.append("&port=").append(currentPort);
        if (currentPassword != null && !currentPassword.isEmpty()) {
            urlBuilder.append("&password=").append(currentPassword);
        }

        final String connectionUrl = urlBuilder.toString();

        uiHandler.post(() -> {
            if (webView != null) {
                webView.loadUrl(connectionUrl);
                // Consider connection successful once WebView loads
                if (statusCallback != null) {
                    statusCallback.onVncConnected();
                }
            }
        });
    }

    /**
     * Disconnect from VNC server
     */
    public void disconnect() {
        this.isConnected = false;
        this.currentHost = null;
        this.currentPort = 0;
        this.currentPassword = null;

        uiHandler.post(() -> {
            if (webView != null) {
                // Call JavaScript disconnect function
                webView.evaluateJavascript("javascript:if(window.vncViewer) vncViewer.disconnect();", null);
            }
        });

        if (statusCallback != null) {
            statusCallback.onVncDisconnected();
        }
    }

    /**
     * Pause the VNC connection
     */
    public void pause() {
        // For WebView, we can just stop loading or hide it
        uiHandler.post(() -> {
            if (webView != null) {
                webView.onPause();
            }
        });
    }

    /**
     * Resume the VNC connection
     */
    public void resume() {
        uiHandler.post(() -> {
            if (webView != null) {
                webView.onResume();
            }
        });
    }

    /**
     * Check if VNC server is running
     */
    public void checkVNCServerStatus(final ServerStatusCallback callback) {
        if (serviceClient == null) {
            callback.onResult(false);
            return;
        }

        commandExecutor.checkVNCServerRunning(serviceClient, new LinuxCommandExecutor.ServerCheckCallback() {
            @Override
            public void onResult(boolean isRunning) {
                callback.onResult(isRunning);
            }
        });
    }

    /**
     * Callback interface for server status checks
     */
    public interface ServerStatusCallback {
        void onResult(boolean isRunning);
    }

    /**
     * Get connection status
     */
    public boolean isConnected() {
        return isConnected;
    }

    /**
     * JavaScript interface methods (called from WebView)
     */
    public void onVncConnected() {
        this.isConnected = true;
        Log.d(TAG, "VNC connection successful");
        if (statusCallback != null) {
            uiHandler.post(() -> statusCallback.onVncConnected());
        }
    }

    public void onVncConnectionFailed(String error) {
        this.isConnected = false;
        Log.e(TAG, "VNC connection failed: " + error);
        if (statusCallback != null) {
            uiHandler.post(() -> statusCallback.onVncConnectionFailed(error));
        }
    }

    public void onVncDisconnected() {
        this.isConnected = false;
        Log.d(TAG, "VNC disconnected");
        if (statusCallback != null) {
            uiHandler.post(() -> statusCallback.onVncDisconnected());
        }
    }
}