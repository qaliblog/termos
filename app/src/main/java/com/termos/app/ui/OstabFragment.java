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
        statusOverlay = root.findViewById(R.id.vnc_status_overlay);
        statusTitle = root.findViewById(R.id.vnc_status_title);
        statusMessage = root.findViewById(R.id.vnc_status_message);
        helpText = root.findViewById(R.id.vnc_help_text);
        loadingProgress = root.findViewById(R.id.vnc_loading);

        vncManager = VNCConnectionManager.getInstance(activity);
        vncManager.setStatusCallback(this);
        uiHandler = new Handler(Looper.getMainLooper());

        // Start with status overlay visible
        showStatusOverlay("OS Desktop", "Initializing VNC connection...", "");

        return root;
    }


    @Override
    public void onResume() {
        super.onResume();

        // Initialize and connect VNC when fragment becomes visible
        if (vncManager != null && vncCanvas != null && activity != null) {
            try {
                showStatusOverlay("OS Desktop", "Initializing VNC connection...", "");

                vncManager.initialize(vncCanvas, activity);

                // Set service client for command execution
                TermuxActivity termuxActivity = (TermuxActivity) activity;
                if (termuxActivity.getTermuxService() != null) {
                    vncManager.setServiceClient(termuxActivity.getTermuxService().getTermuxTerminalSessionClient());
                }

                // Start connection with status updates
                connectWithStatusUpdates();

            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize VNC connection", e);
                showErrorStatus("Connection Failed", "Failed to initialize VNC: " + e.getMessage());
            }
        } else {
            showErrorStatus("Setup Error", "VNC components not available");
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
     * Connect to VNC with status updates
     */
    private void connectWithStatusUpdates() {
        try {
            showStatusOverlay("OS Desktop", "Starting VNC server...", "");

            // Start connection after a brief delay to show status
            uiHandler.postDelayed(() -> {
                if (vncManager != null) {
                    showStatusOverlay("OS Desktop", "Connecting to VNC server...\nWill retry every 5 seconds", "");
                    vncManager.connect();
                }
            }, 500);

            // Update status periodically to show retry attempts
            startRetryStatusUpdates();

            // Don't show timeout error too early - let the retry mechanism work
            // The connection will retry every 5 seconds, so give it more time
            uiHandler.postDelayed(() -> {
                if (statusOverlay != null && statusOverlay.getVisibility() == View.VISIBLE && !isVncConnected) {
                    showErrorStatus("Connection Timeout",
                        "VNC connection is taking longer than expected.\n\n" +
                        "This usually means:\n" +
                        "• VNC server is not running\n" +
                        "• Desktop environment is not installed\n" +
                        "• Network connection issues\n\n" +
                        "The app will continue retrying every 5 seconds.\n" +
                        "Try running the desktop diagnostic in a terminal.");
                }
            }, 120000); // 2 minute timeout before showing error

        } catch (Exception e) {
            Log.e(TAG, "Error in connectWithStatusUpdates", e);
            showErrorStatus("Connection Error", e.getMessage());
        }
    }

    /**
     * Start periodic status updates showing retry attempts
     */
    private void startRetryStatusUpdates() {
        Runnable statusUpdater = new Runnable() {
            private int retryCount = 0;

            @Override
            public void run() {
                if (statusOverlay == null || statusOverlay.getVisibility() != View.VISIBLE || isVncConnected) {
                    return; // Stop updating if connected or overlay hidden
                }

                retryCount++;
                String retryText = retryCount == 1 ? "Connecting to VNC server..." :
                                 String.format("Retrying connection (attempt %d)...", retryCount);

                uiHandler.post(() -> {
                    if (statusMessage != null) {
                        statusMessage.setText(retryText + "\nWill retry every 5 seconds");
                    }
                });

                // Schedule next update in 5 seconds
                uiHandler.postDelayed(this, 5000);
            }
        };

        // Start status updates after 2 seconds
        uiHandler.postDelayed(statusUpdater, 2000);
    }

    /**
     * Show status overlay with given message
     */
    private void showStatusOverlay(String title, String message, String helpText) {
        if (statusOverlay == null || statusTitle == null || statusMessage == null) {
            return;
        }

        uiHandler.post(() -> {
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
            showErrorStatus("Connection Failed", error);
            Log.e(TAG, "VNC connection failed: " + error);
        });
    }
}
