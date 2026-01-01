package com.termos.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.webkit.WebView;
import com.termos.R;
import com.termos.app.TermuxActivity;
import com.termos.app.vnc.WebViewVNCManager;

/**
 * Fragment for the OS tab.
 * Embeds WebView-based VNC viewer to display Linux desktop environment.
 */
public class OstabFragment extends Fragment implements WebViewVNCManager.VNCStatusCallback {

    private static final String TAG = "OstabFragment";

    private Activity activity;
    private WebView vncWebView;
    private WebViewVNCManager vncManager;
    private ScrollView connectionForm;
    private LinearLayout statusOverlay;
    private TextView statusTitle;
    private TextView statusMessage;
    private TextView helpText;
    private ProgressBar loadingProgress;
    private Handler uiHandler;
    private boolean isVncConnected = false;

    // Simple VNC viewer container
    private android.widget.FrameLayout vncContainer;


    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        this.activity = activity;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {

        View root = inflater.inflate(R.layout.fragment_os_tab, container, false);

        // Initialize UI elements
        vncWebView = root.findViewById(R.id.vnc_webview);
        vncContainer = root.findViewById(R.id.vnc_container);
        connectionForm = root.findViewById(R.id.vnc_connection_form);
        statusOverlay = root.findViewById(R.id.vnc_status_overlay);
        statusTitle = root.findViewById(R.id.vnc_status_title);
        statusMessage = root.findViewById(R.id.vnc_status_message);
        helpText = root.findViewById(R.id.vnc_help_text);
        loadingProgress = root.findViewById(R.id.vnc_loading);

        // Set up connection form
        setupConnectionForm(root);

        // Initialize WebView-based VNC manager
        vncManager = new WebViewVNCManager(activity, activity);
        vncManager.setStatusCallback(this);
        uiHandler = new Handler(Looper.getMainLooper());

        // Start with connection form visible
        showConnectionForm();

        return root;
    }


    @Override
    public void onResume() {
        super.onResume();

        // Initialize VNC manager when fragment becomes visible
        if (vncManager != null && vncWebView != null && activity != null) {
            try {
                // Initialize WebView for VNC
                vncManager.initialize(vncWebView);

                // Set service client for command execution
                TermuxActivity termuxActivity = (TermuxActivity) activity;
                if (termuxActivity.getTermuxService() != null) {
                    vncManager.setServiceClient(termuxActivity.getTermuxService().getTermuxTerminalSessionClient());
                }

                // Resume WebView
                vncWebView.onResume();
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize VNC connection", e);
                showErrorStatus("Connection Failed", "Failed to initialize VNC: " + e.getMessage());
            }

            // Show connection form instead of auto-connecting
            showConnectionForm();
        } else {
            showErrorStatus("Setup Error", "VNC components not available");
        }
}

    /**
     * Set up the VNC connection form event listeners
     */
    private void setupConnectionForm(View root) {
        com.google.android.material.button.MaterialButton connectButton = root.findViewById(R.id.vnc_connect_button);
        com.google.android.material.button.MaterialButton cancelButton = root.findViewById(R.id.vnc_cancel_button);

        connectButton.setOnClickListener(v -> {
            com.google.android.material.textfield.TextInputEditText hostInput = root.findViewById(R.id.vnc_host_input);
            com.google.android.material.textfield.TextInputEditText portInput = root.findViewById(R.id.vnc_port_input);
            com.google.android.material.textfield.TextInputEditText usernameInput = root.findViewById(R.id.vnc_username_input);
            com.google.android.material.textfield.TextInputEditText passwordInput = root.findViewById(R.id.vnc_password_input);

            String host = hostInput.getText().toString().trim();
            String portStr = portInput.getText().toString().trim();
            String username = usernameInput.getText().toString().trim();
            String password = passwordInput.getText().toString();

            if (host.isEmpty()) {
                hostInput.setError("Host is required");
                return;
            }

            if (portStr.isEmpty()) {
                portInput.setError("Port is required");
                return;
            }

            int port;
            try {
                port = Integer.parseInt(portStr);
                if (port < 1 || port > 65535) {
                    portInput.setError("Port must be between 1 and 65535");
                    return;
                }
            } catch (NumberFormatException e) {
                portInput.setError("Invalid port number");
                return;
            }

            // Start connection
            connectToVNC(host, port, username, password);
        });

        cancelButton.setOnClickListener(v -> {
            // Cancel connection and return to form
            if (vncManager != null) {
                vncManager.disconnect();
            }
            showConnectionForm();
        });
    }

    /**
     * Connect to VNC server with the specified parameters
     */
    private void connectToVNC(String host, int port, String username, String password) {
        showStatusOverlay("Connecting",
            "Connecting to VNC server...\n" +
            "Host: " + host + "\n" +
            "Port: " + port + "\n" +
            "Username: " + (username.isEmpty() ? "(none)" : username) + "\n" +
            "Trying different authentication methods automatically", "");

        Log.d(TAG, "Attempting VNC connection to " + host + ":" + port + " as user: " + (username.isEmpty() ? "(none)" : username));

        try {
            vncManager.connect(host, port, username.isEmpty() ? null : username, password);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start VNC connection", e);
            showErrorStatus("Connection Failed", "Failed to start connection: " + e.getMessage());
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        // Pause VNC connection when fragment becomes hidden
        if (vncManager != null) {
            vncManager.pause();
        }

        // Pause WebView
        if (vncWebView != null) {
            vncWebView.onPause();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // Disconnect VNC when fragment is destroyed
        if (vncManager != null) {
            vncManager.disconnect();
        }

        // Clean up WebView
        if (vncWebView != null) {
            vncWebView.destroy();
            vncWebView = null;
        }

        // Clean up handler
        if (uiHandler != null) {
            uiHandler.removeCallbacksAndMessages(null);
        }
    }


    /**
     * Show connection form
     */
    private void showConnectionForm() {
        uiHandler.post(() -> {
            if (connectionForm != null) {
                connectionForm.setVisibility(View.VISIBLE);
            }
            if (statusOverlay != null) {
                statusOverlay.setVisibility(View.GONE);
            }
            if (vncContainer != null) {
                vncContainer.setVisibility(View.GONE);
            }
        });
    }

    /**
     * Show status overlay with given message
     */
    private void showStatusOverlay(String title, String message, String helpText) {
        if (statusOverlay == null || statusTitle == null || statusMessage == null) {
            return;
        }

        uiHandler.post(() -> {
            if (connectionForm != null) {
                connectionForm.setVisibility(View.GONE);
            }
            statusTitle.setText(title);
            statusMessage.setText(message);
            if (helpText != null && !helpText.isEmpty()) {
                this.helpText.setText(helpText);
                this.helpText.setVisibility(View.VISIBLE);
            } else {
                this.helpText.setVisibility(View.GONE);
            }
            statusOverlay.setVisibility(View.VISIBLE);
            if (vncContainer != null) {
                vncContainer.setVisibility(View.GONE);
            }
        });
    }

    /**
     * Show error status
     */
    private void showErrorStatus(String title, String message) {
        showStatusOverlay(title, message,
            "Try:\n• Install OS from Settings\n• Check terminal for VNC server status\n• Restart the app");
    }

    /**
     * Hide status overlay and show VNC viewer
     */
    public void showVncCanvas() {
        uiHandler.post(() -> {
            if (connectionForm != null) {
                connectionForm.setVisibility(View.GONE);
            }
            if (statusOverlay != null) {
                statusOverlay.setVisibility(View.GONE);
            }
            if (vncContainer != null) {
                vncContainer.setVisibility(View.VISIBLE);
            }
        });
    }

    /**
     * Called by VNC manager when connection succeeds
     */
    @Override
    public void onVncConnected() {
        isVncConnected = true;
        uiHandler.post(() -> {
            showVncCanvas();
            Log.d(TAG, "VNC connection successful - showing WebView");
        });
    }

    /**
     * Called by VNC manager when connection fails
     */
    @Override
    public void onVncConnectionFailed(String error) {
        uiHandler.post(() -> {
            showErrorStatus("Connection Failed",
                "Unable to connect to VNC server.\n\n" +
                "Error: " + error + "\n\n" +
                "Make sure:\n" +
                "• VNC server is running on the specified host/port\n" +
                "• Password is correct\n" +
                "• Network connectivity is available\n\n" +
                "For local connections, use 127.0.0.1 and port 5901.\n\n" +
                "Tap Cancel to return to connection form.");
            Log.e(TAG, "VNC connection failed: " + error);
        });
    }

    /**
     * Called by VNC manager when disconnected
     */
    @Override
    public void onVncDisconnected() {
        isVncConnected = false;
        uiHandler.post(() -> {
            showConnectionForm();
            Log.d(TAG, "VNC disconnected");
        });
    }
}
