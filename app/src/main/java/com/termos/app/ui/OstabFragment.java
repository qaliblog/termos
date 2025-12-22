package com.termos.app.ui;

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
    
    private VNCConnectionManager vncManager;
    private RemoteCanvas vncCanvas;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_os_tab, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Get VNC connection manager
        vncManager = VNCConnectionManager.getInstance(requireContext());
        
        // Get RemoteCanvas from layout
        vncCanvas = view.findViewById(R.id.vnc_canvas);
        
        if (vncCanvas == null) {
            Log.e(TAG, "RemoteCanvas not found in layout");
            return;
        }
        
        // Get activity - must use Activity context for RemoteConnection (needs it for dialogs)
        Activity activity = requireActivity();
        
        // Delay initialization until view is fully attached and activity window is ready
        // This ensures the activity has a valid window token for showing dialogs
        // Use post() to ensure the view is attached and the activity window is ready
        vncCanvas.post(() -> {
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                try {
                    vncManager.initialize(vncCanvas, activity);
                    
                    // Set service client for command execution (auto-start VNC server)
                    TermuxActivity termuxActivity = (TermuxActivity) activity;
                    if (termuxActivity.getTermuxService() != null) {
                        vncManager.setServiceClient(termuxActivity.getTermuxService().getTermuxTerminalSessionClient());
                    } else {
                        Log.w(TAG, "TermuxService not available yet, connection may fail");
                    }
                    
                    Log.d(TAG, "VNC viewer initialized");
                    
                    // Start connection immediately after initialization
                    // The connection will handle starting the VNC server if needed
                    vncManager.connect();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to initialize VNC connection", e);
                    // If initialization fails due to window token, try once more after a delay
                    if (e instanceof android.view.WindowManager.BadTokenException 
                            || (e.getCause() != null && e.getCause() instanceof android.view.WindowManager.BadTokenException)) {
                        vncCanvas.postDelayed(() -> {
                            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                                try {
                                    vncManager.initialize(vncCanvas, activity);
                                    TermuxActivity termuxActivity = (TermuxActivity) activity;
                                    if (termuxActivity.getTermuxService() != null) {
                                        vncManager.setServiceClient(termuxActivity.getTermuxService().getTermuxTerminalSessionClient());
                                    }
                                    Log.d(TAG, "VNC viewer initialized (retry)");
                                    
                                    // Start connection after retry initialization
                                    vncManager.connect();
                                } catch (Exception retryException) {
                                    Log.e(TAG, "Failed to initialize VNC connection (retry)", retryException);
                                }
                            }
                        }, 200); // 200ms delay for retry
                    }
                }
            }
        });
    }
    
    @Override
    public void onResume() {
        super.onResume();
        
        // Connect or resume VNC connection when tab becomes visible
        if (vncManager != null) {
            if (vncManager.isConnected()) {
                vncManager.resume();
            } else {
                vncManager.connect();
            }
        }
    }
    
    @Override
    public void onPause() {
        super.onPause();
        
        // Pause VNC connection when tab becomes hidden (but don't disconnect)
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
    
    public VNCConnectionManager getVncManager() {
        return vncManager;
    }
}

