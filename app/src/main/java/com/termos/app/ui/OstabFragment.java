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

import com.iiordanov.bVNC.RemoteCanvas;
import com.termos.R;
import com.termos.app.TermuxActivity;
import com.termos.app.vnc.VNCConnectionManager;

/**
 * Fragment for the OS tab.
 * Embeds bVNC viewer to display Linux desktop environment via VNC.
 */
public class OstabFragment extends Fragment {

    private static final String TAG = "OstabFragment";

    private Activity activity;
    private RemoteCanvas vncCanvas;
    private VNCConnectionManager vncManager;
    private ScrollView connectionForm;
    private LinearLayout statusOverlay;
    private TextView statusTitle;
    private TextView statusMessage;
    private TextView helpText;
    private ProgressBar loadingProgress;
    private Handler uiHandler;
    private boolean isVncConnected = false;


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
        vncCanvas = root.findViewById(R.id.vnc_canvas);
        connectionForm = root.findViewById(R.id.vnc_connection_form);
        statusOverlay = root.findViewById(R.id.vnc_status_overlay);
        statusTitle = root.findViewById(R.id.vnc_status_title);
        statusMessage = root.findViewById(R.id.vnc_status_message);
        helpText = root.findViewById(R.id.vnc_help_text);
        loadingProgress = root.findViewById(R.id.vnc_loading);

        // Set up connection form
        setupConnectionForm(root);

        vncManager = VNCConnectionManager.getInstance(activity);
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
        if (vncManager != null && vncCanvas != null && activity != null) {
            try {
                vncManager.initialize(vncCanvas, activity);

                // Set service client for command execution
                TermuxActivity termuxActivity = (TermuxActivity) activity;
                if (termuxActivity.getTermuxService() != null) {
                    vncManager.setServiceClient(termuxActivity.getTermuxService().getTermuxTerminalSessionClient());
                }

        // Show connection form instead of auto-connecting
        showConnectionForm();

    } catch (Exception e) {
        Log.e(TAG, "Failed to initialize VNC connection", e);
        showErrorStatus("Connection Failed", "Failed to initialize VNC: " + e.getMessage());
    }
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
            com.google.android.material.textfield.TextInputEditText passwordInput = root.findViewById(R.id.vnc_password_input);

            String host = hostInput.getText().toString().trim();
            String portStr = portInput.getText().toString().trim();
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

            // Password is optional - some VNC servers don't require authentication
            // if (password.isEmpty()) {
            //     passwordInput.setError("Password is required");
            //     return;
            // }

            // Start connection
            connectToVNC(host, port, password);
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
    private void connectToVNC(String host, int port, String password) {
        showStatusOverlay("Connecting",
            "Connecting to VNC server...\n" +
            "Host: " + host + "\n" +
            "Port: " + port + "\n" +
            "Trying different authentication methods automatically", "");

        Log.d(TAG, "Attempting VNC connection to " + host + ":" + port);

        try {
            vncManager.connect(host, port, password);
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
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // Disconnect VNC when fragment is destroyed
        if (vncManager != null) {
            vncManager.disconnect();
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
            if (vncCanvas != null) {
                vncCanvas.setVisibility(View.GONE);
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
            vncCanvas.setVisibility(View.GONE);
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
     * Hide status overlay and show VNC canvas
     */
    public void showVncCanvas() {
        uiHandler.post(() -> {
            if (connectionForm != null) {
                connectionForm.setVisibility(View.GONE);
            }
            if (statusOverlay != null) {
                statusOverlay.setVisibility(View.GONE);
            }
            if (vncCanvas != null) {
                vncCanvas.setVisibility(View.VISIBLE);
            }
        });
    }

    /**
     * Called by VNC manager when connection succeeds
     */
    public void onVncConnected() {
        isVncConnected = true;
        uiHandler.post(() -> {
            showVncCanvas();
            Log.d(TAG, "VNC connection successful - showing canvas");
        });
    }

    /**
     * Called by VNC manager when connection fails
     */
    public void onVncConnectionFailed(String error) {
        uiHandler.post(() -> {
            showErrorStatus("Connection Failed",
                error + "\n\nPossible solutions:\n" +
                "• Verify host, port, and password match your VNC server\n" +
                "• Try different VNC ports (5900-5909)\n" +
                "• Check if password is required or try empty password\n" +
                "• Ensure VNC server supports VNC authentication\n" +
                "• Try connecting with different credentials\n\n" +
                "Tap Cancel to return to connection form.");
            Log.e(TAG, "VNC connection failed: " + error);
        });
    }
}
