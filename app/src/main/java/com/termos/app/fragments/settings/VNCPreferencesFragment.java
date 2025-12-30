package com.termos.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.termos.R;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

@Keep
public class VNCPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(VNCPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.vnc_preferences, rootKey);
    }

}

class VNCPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final TermuxAppSharedPreferences mPreferences;

    private static VNCPreferencesDataStore mInstance;

    private VNCPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = TermuxAppSharedPreferences.build(context, true);
    }

    public static synchronized VNCPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new VNCPreferencesDataStore(context);
        }
        return mInstance;
    }

    @Override
    public String getString(String key, String defValue) {
        if ("vnc_viewer_type".equals(key)) {
            return mPreferences.getVNCViewerType();
        } else if ("vnc_input_mode".equals(key)) {
            return mPreferences.getVNCInputMode();
        }
        return defValue;
    }

    @Override
    public void putString(String key, String value) {
        if ("vnc_viewer_type".equals(key)) {
            mPreferences.setVNCViewerType(value);
        } else if ("vnc_input_mode".equals(key)) {
            mPreferences.setVNCInputMode(value);
        }
    }

}