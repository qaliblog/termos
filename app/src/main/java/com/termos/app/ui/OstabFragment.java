package com.termos.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.iiordanov.bVNC.RemoteCanvas;
import com.termos.R;
import com.termos.app.TermuxActivity;
import com.termos.app.vnc.VNCConnectionManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * OS tab fragment.
 * Polls for a running VNC server every 5 seconds and connects when available.
 */
public class OstabFragment extends Fragment {

    private static final String TAG = "OstabFragment";
    private static final long CHECK_INTERVAL_MS = 5000;

    private Activity activity;
    private Handler handler;

    private RemoteCanvas vncCanvas;
    private VNCConnectionManager vncManager;

    private final Runnable vncPollRunnable = new Runnable() {
        @Override
        public void run() {
            checkAndConnectVNC();
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

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

        vncCanvas = root.findViewById(R.id.vnc_canvas);
        vncManager = new VNCConnectionManager(activity, vncCanvas);

        handler = new Handler(Looper.getMainLooper());
        handler.post(vncPollRunnable);

        return root;
    }

    /**
     * Reads the saved VNC port and connects if needed.
     * Does not guess ports.
     */
    private void checkAndConnectVNC() {
        try {
            if (vncManager == null) {
                return;
            }

            // If session is alive, nothing to do
            if (vncManager.isSessionAlive()) {
                return;
            }

            File portFile = new File(
                    TermuxActivity.getTermuxHome(),
                    ".vnc/termos_vnc_port"
            );

            if (!portFile.exists()) {
                Log.d(TAG, "VNC port file not found yet");
                return;
            }

            BufferedReader reader = new BufferedReader(new FileReader(portFile));
            String portLine = reader.readLine();
            reader.close();

            if (portLine == null || portLine.trim().isEmpty()) {
                return;
            }

            int port = Integer.parseInt(portLine.trim());

            Log.i(TAG, "Attempting VNC connection on port " + port);

            vncManager.disconnect();
            vncManager.connect();

        } catch (Exception e) {
            Log.e(TAG, "Error while checking VNC status", e);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (handler != null) {
            handler.removeCallbacks(vncPollRunnable);
        }

        if (vncManager != null) {
            vncManager.disconnect();
        }
    }
}
