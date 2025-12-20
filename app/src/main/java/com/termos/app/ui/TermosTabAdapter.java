package com.termos.app.ui;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import com.termos.R;

/**
 * Adapter for managing Terminal and OS tabs.
 */
public class TermosTabAdapter extends FragmentPagerAdapter {
    
    private static final int TAB_COUNT = 2;
    private static final int TAB_TERMINAL = 0;
    private static final int TAB_OS = 1;
    
    private TerminalTabFragment terminalTabFragment;
    private OstabFragment osTabFragment;
    private Context context;
    
    public TermosTabAdapter(@NonNull FragmentManager fm, int behavior, Context context) {
        super(fm, behavior);
        this.context = context;
    }
    
    @NonNull
    @Override
    public Fragment getItem(int position) {
        switch (position) {
            case TAB_TERMINAL:
                if (terminalTabFragment == null) {
                    terminalTabFragment = new TerminalTabFragment();
                }
                return terminalTabFragment;
            case TAB_OS:
                if (osTabFragment == null) {
                    osTabFragment = new OstabFragment();
                }
                return osTabFragment;
            default:
                return new TerminalTabFragment();
        }
    }
    
    @Override
    public int getCount() {
        return TAB_COUNT;
    }
    
    @Override
    public CharSequence getPageTitle(int position) {
        if (context == null) {
            return "";
        }
        switch (position) {
            case TAB_TERMINAL:
                return context.getString(R.string.tab_terminal_title);
            case TAB_OS:
                return context.getString(R.string.tab_os_title);
            default:
                return "";
        }
    }
    
    public TerminalTabFragment getTerminalTabFragment() {
        return terminalTabFragment;
    }
    
    public OstabFragment getOsTabFragment() {
        return osTabFragment;
    }
}

