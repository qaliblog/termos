package com.termos.app.ui;

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

import java.lang.ref.WeakReference;

import android.opengl.GLSurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.termos.R;
import com.termos.app.TermuxActivity;
import com.termos.app.model.ServerProfile;
import com.termos.app.ui.vnc.FrameView;
import com.termos.app.viewmodel.VncViewModel;

/**
 * Fragment for the OS tab.
 * Uses AVNC library for internal VNC connections to display Linux desktop environment.
 */
public class OstabFragment extends Fragment {

    private static final String TAG = "OstabFragment";

    private Activity activity;
    private VncViewModel vncViewModel;
    private FrameView vncFrameView;
    private ScrollView connectionForm;
    private LinearLayout statusOverlay;
    private TextView statusTitle;
    private TextView statusMessage;
    private TextView helpText;
    private ProgressBar loadingProgress;
    private Handler uiHandler;
    private boolean isVncConnected = false;

    // VNC canvas container
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
        vncFrameView = root.findViewById(R.id.vnc_frame_view);
        vncContainer = root.findViewById(R.id.vnc_container);
        connectionForm = root.findViewById(R.id.vnc_connection_form);
        statusOverlay = root.findViewById(R.id.vnc_status_overlay);
        statusTitle = root.findViewById(R.id.vnc_status_title);
        statusMessage = root.findViewById(R.id.vnc_status_message);
        helpText = root.findViewById(R.id.vnc_help_text);
        loadingProgress = root.findViewById(R.id.vnc_loading);

        // Set up connection form
        setupConnectionForm(root);

        // Initialize AVNC ViewModel
        vncViewModel = new ViewModelProvider(this).get(VncViewModel.class);
        uiHandler = new Handler(Looper.getMainLooper());

        // Observe VNC connection state
        observeVncState();

        // Start with connection form visible
        showConnectionForm();

        return root;
    }

    /**
     * Observe VNC connection state changes
     */
    private void observeVncState() {
        if (vncViewModel != null) {
            vncViewModel.getState().observe(getViewLifecycleOwner(), state -> {
                if (state.isConnected()) {
                    isVncConnected = true;
                    showVncCanvas();
                    Log.d(TAG, "VNC connection successful - showing FrameView");
                } else if (state.isDisconnected()) {
                    isVncConnected = false;
                    showConnectionForm();
                    Log.d(TAG, "VNC disconnected");
                } else {
                    // Handle other states (connecting, error, etc.)
                    showStatusOverlay("Connecting", "Connecting to VNC server...", "");
                }
            });

            vncViewModel.getConnectionError().observe(getViewLifecycleOwner(), error -> {
                if (error != null) {
                    showErrorStatus("Connection Failed", "Unable to connect to VNC server.\n\nError: " + error);
                    Log.e(TAG, "VNC connection failed: " + error);
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // Resume FrameView when fragment becomes visible
        if (vncFrameView != null) {
            vncFrameView.onResume();
        }

        // Initialize VNC ViewModel when fragment becomes visible
        if (vncViewModel != null && vncFrameView != null && activity != null) {
            try {
                // Set up FrameView for VNC display - basic setup without VncActivity dependencies
                if (!vncFrameView.getRenderer().equals(null)) {
                    // Renderer not set yet, set it up
                    vncFrameView.setEGLContextClientVersion(2);
                    // Note: Renderer will be set by VncViewModel when connection starts
                    vncFrameView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
                }
                vncViewModel.frameViewRef = new WeakReference<>(vncFrameView);

                // Set service client for command execution
                TermuxActivity termuxActivity = (TermuxActivity) activity;
                if (termuxActivity.getTermuxService() != null) {
                    // Note: AVNC doesn't use the same service client pattern as BVNC
                    // We may need to adapt this for command execution
                }
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
            if (vncViewModel != null) {
                vncViewModel.disconnect();
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
            "Using AVNC for connection", "");

        Log.d(TAG, "Attempting VNC connection to " + host + ":" + port + " as user: " + (username.isEmpty() ? "(none)" : username));

        try {
            // Create a ServerProfile for AVNC
            ServerProfile profile = new ServerProfile();
            profile.setHost(host);
            profile.setPort(port);
            profile.setUsername(username.isEmpty() ? "" : username);
            profile.setPassword(password);
            profile.setSecurityType(0); // 0 = enable all supported security types

            // Connect using VncViewModel
            vncViewModel.initConnection(profile);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start VNC connection", e);
            showErrorStatus("Connection Failed", "Failed to start connection: " + e.getMessage());
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        // Pause FrameView when fragment becomes hidden
        if (vncFrameView != null) {
            vncFrameView.onPause();
        }

        // Pause VNC connection when fragment becomes hidden
        if (vncViewModel != null) {
            vncViewModel.pause();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // Disconnect VNC when fragment is destroyed
        if (vncViewModel != null) {
            vncViewModel.disconnect();
        }

        // Clean up FrameView
        if (vncFrameView != null) {
            vncFrameView = null;
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

}
