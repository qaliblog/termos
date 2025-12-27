package com.termos.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.app.Activity;
import android.os.Bundle;
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

/**
 * Fragment for the OS tab.
 * Embeds bVNC viewer to display Linux desktop environment via VNC.
 */
public class OstabFragment extends Fragment {

    private static final String TAG = "OstabFragment";

    private Activity activity;
    private RemoteCanvas vncCanvas;
    private VNCConnectionManager vncManager;


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
        vncManager = VNCConnectionManager.getInstance(activity);

        return root;
    }


    @Override
    public void onResume() {
        super.onResume();

        // Initialize and connect VNC when fragment becomes visible
        if (vncManager != null && vncCanvas != null && activity != null) {
            try {
                vncManager.initialize(vncCanvas, activity);

                // Set service client for command execution
                TermuxActivity termuxActivity = (TermuxActivity) activity;
                if (termuxActivity.getTermuxService() != null) {
                    vncManager.setServiceClient(termuxActivity.getTermuxService().getTermuxTerminalSessionClient());
                }

                // Start connection
                vncManager.connect();
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize VNC connection", e);
            }
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
    }
}
