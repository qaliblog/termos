package com.termos.app.ui.prefs;

import android.os.Bundle;
import androidx.preference.PreferenceFragmentCompat;

import com.termos.R;

/**
 * Fragment for VNC preferences.
 * Uses AVNC preference screens.
 */
public class VNCPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // Load the main AVNC preferences
        setPreferencesFromResource(R.xml.pref_main, rootKey);
    }
}