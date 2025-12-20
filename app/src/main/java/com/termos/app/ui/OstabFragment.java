package com.termos.app.ui;

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
        
        // Initialize VNC connection manager
        vncManager.initialize(vncCanvas);
        
        // Set service client for command execution (auto-start VNC server)
        TermuxActivity activity = (TermuxActivity) getActivity();
        if (activity != null && activity.getTermuxService() != null) {
            vncManager.setServiceClient(activity.getTermuxService().getTermuxTerminalSessionServiceClient());
        }
        
        Log.d(TAG, "VNC viewer initialized");
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

