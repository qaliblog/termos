package com.termos.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termos.app.TermuxActivity;
import com.termux.view.TerminalView;
import com.termos.R;

/**
 * Fragment containing the Terminal tab.
 * Preserves all existing TerminalView functionality.
 */
public class TerminalTabFragment extends Fragment {
    
    private TerminalView mTerminalView;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_terminal_tab, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Get TerminalView from layout
        mTerminalView = view.findViewById(R.id.terminal_view);
        
        // Notify activity that TerminalView is ready
        TermuxActivity activity = (TermuxActivity) getActivity();
        if (activity != null && mTerminalView != null) {
            activity.onTerminalViewReady(mTerminalView);
        }
    }
    
    @Override
    public void onResume() {
        super.onResume();
        // Terminal view will be resumed by TermuxActivity
    }
    
    @Override
    public void onPause() {
        super.onPause();
        // Terminal view will be paused by TermuxActivity
    }
    
    public TerminalView getTerminalView() {
        return mTerminalView;
    }
}

