package com.iiordanov.pubkeygenerator;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Activity for generating SSH public/private key pairs.
 * This is a placeholder implementation that returns the existing private key.
 */
public class GeneratePubkeyActivity extends Activity {
    private static final String TAG = "GeneratePubkeyActivity";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Get the existing private key from intent
        String privateKey = getIntent().getStringExtra("PrivateKey");
        
        if (privateKey == null || privateKey.isEmpty()) {
            Log.w(TAG, "No private key provided, generating new key pair");
            // TODO: Implement key generation if needed
            // For now, just return the result with the existing key
        }
        
        // Return the result with the private key
        Intent resultIntent = new Intent();
        if (privateKey != null) {
            resultIntent.putExtra("PrivateKey", privateKey);
        }
        setResult(RESULT_OK, resultIntent);
        finish();
    }
}
