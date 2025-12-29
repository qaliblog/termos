package com.termos.app.activities;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termos.R;
import com.termos.app.TermuxActivity;
import com.termos.app.linuxruntime.RootfsDownloader;
import com.termos.app.linuxruntime.RootfsManager;

import java.io.File;

/**
 * Rootfs setup activity - shown on first launch when no rootfs is installed.
 * Allows user to select and download a Linux distribution rootfs.
 */
public class RootfsSetupActivity extends AppCompatActivity {
    
    private RootfsManager rootfsManager;
    private RadioGroup rootfsTypeGroup;
    private RadioButton alpineRadio;
    private RadioButton ubuntuRadio;
    private RadioButton ubuntu22Radio;
    private RadioButton kaliRadio;
    private LinearLayout customUrlLayout;
    private EditText customUrlEdit;
    private EditText rootfsNameEdit;
    private Button installButton;
    private ProgressBar progressBar;
    private TextView statusText;

    private static final int TYPE_ALPINE = 0;
    private static final int TYPE_UBUNTU = 1;
    private static final int TYPE_UBUNTU22 = 2;
    private static final int TYPE_KALI = 3;
    private static final int TYPE_CUSTOM = 4;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rootfs_setup);
        
        rootfsManager = new RootfsManager(this);
        
        // Check if already installed
        if (rootfsManager.isRootfsInstalled()) {
            finishSetup();
            return;
        }
        
        initializeViews();
        setupListeners();
    }
    
    private void initializeViews() {
        rootfsTypeGroup = findViewById(R.id.rootfs_type_group);
        alpineRadio = findViewById(R.id.rootfs_alpine);
        ubuntuRadio = findViewById(R.id.rootfs_ubuntu);
        ubuntu22Radio = findViewById(R.id.rootfs_ubuntu22);
        kaliRadio = findViewById(R.id.rootfs_kali);
        customUrlLayout = findViewById(R.id.custom_url_layout);
        customUrlEdit = findViewById(R.id.custom_url_edit);
        rootfsNameEdit = findViewById(R.id.rootfs_name_edit);
        installButton = findViewById(R.id.install_button);
        progressBar = findViewById(R.id.progress_bar);
        statusText = findViewById(R.id.status_text);

        // Hide custom URL layout initially
        customUrlLayout.setVisibility(View.GONE);
    }
    
    private void setupListeners() {
        rootfsTypeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rootfs_custom) {
                customUrlLayout.setVisibility(View.VISIBLE);
            } else {
                customUrlLayout.setVisibility(View.GONE);
            }
        });
        
        installButton.setOnClickListener(v -> startInstallation());
    }
    
    private void startInstallation() {
        int selectedType = getSelectedType();
        
        if (selectedType == TYPE_CUSTOM) {
            String customUrl = customUrlEdit.getText().toString().trim();
            if (customUrl.isEmpty()) {
                Toast.makeText(this, "Please enter a custom URL", Toast.LENGTH_SHORT).show();
                return;
            }
            installRootfs(selectedType, customUrl);
        } else {
            installRootfs(selectedType, null);
        }
    }
    
    private int getSelectedType() {
        int checkedId = rootfsTypeGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.rootfs_alpine) {
            return TYPE_ALPINE;
        } else if (checkedId == R.id.rootfs_ubuntu) {
            return TYPE_UBUNTU;
        } else if (checkedId == R.id.rootfs_ubuntu22) {
            return TYPE_UBUNTU22;
        } else if (checkedId == R.id.rootfs_kali) {
            return TYPE_KALI;
        } else if (checkedId == R.id.rootfs_custom) {
            return TYPE_CUSTOM;
        }
        return TYPE_ALPINE; // Default
    }
    
    @SuppressWarnings("deprecation")
    private void installRootfs(int type, String customUrl) {
        installButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            new InstallRootfsTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, type, customUrl);
        } else {
            new InstallRootfsTask().execute(type, customUrl);
        }
    }
    
    @SuppressWarnings("deprecation")
    private class InstallRootfsTask extends AsyncTask<Object, Integer, Boolean> {
        private String errorMessage;
        private String rootfsFileName;
        private int workingMode;
        
        @Override
        protected Boolean doInBackground(Object... params) {
            try {
                int type = (Integer) params[0];
                String customUrl = params.length > 1 ? (String) params[1] : null;
                
                RootfsDownloader.AbiUrls abiUrls = RootfsDownloader.getAbiUrls();
                
                // Determine rootfs file name and URL
                switch (type) {
                    case TYPE_ALPINE:
                        rootfsFileName = "alpine.tar.gz";
                        workingMode = 0; // ALPINE
                        break;
                    case TYPE_UBUNTU:
                        rootfsFileName = "ubuntu.tar.gz";
                        workingMode = 2; // UBUNTU
                        break;
                    case TYPE_UBUNTU22:
                        rootfsFileName = "ubuntu22.tar.gz";
                        workingMode = 2; // UBUNTU (same mode)
                        break;
                    case TYPE_KALI:
                        rootfsFileName = "kali.tar.gz";
                        workingMode = 2; // UBUNTU mode (Debian-based)
                        break;
                    case TYPE_CUSTOM:
                        // Extract filename from URL or use default
                        if (customUrl != null && customUrl.contains("/")) {
                            rootfsFileName = customUrl.substring(customUrl.lastIndexOf("/") + 1);
                            if (!rootfsFileName.endsWith(".tar.gz") && !rootfsFileName.endsWith(".tar")) {
                                rootfsFileName = "custom_rootfs.tar.gz";
                            }
                        } else {
                            rootfsFileName = "custom_rootfs.tar.gz";
                        }
                        workingMode = 0; // Default to ALPINE mode
                        break;
                    default:
                        rootfsFileName = "alpine.tar.gz";
                        workingMode = 0;
                }
                
                File rootfsDir = rootfsManager.getRootfsDir();
                int totalFiles = 3;
                int completedFiles = 0;
                
                // Download proot
                File prootFile = new File(rootfsDir, "proot");
                if (!prootFile.exists()) {
                    publishProgress(completedFiles * 100 / totalFiles);
                    final int fileIndex = completedFiles;
                    RootfsDownloader.downloadFile(abiUrls.proot, prootFile, 
                        (downloaded, total) -> {
                            int progress = (fileIndex * 100 + (int)(downloaded * 100 / total)) / totalFiles;
                            new Handler(Looper.getMainLooper()).post(() -> publishProgress(progress));
                        });
                }
                completedFiles++;
                
                // Download libtalloc
                File tallocFile = new File(rootfsDir, "libtalloc.so.2");
                if (!tallocFile.exists()) {
                    publishProgress(completedFiles * 100 / totalFiles);
                    final int fileIndex = completedFiles;
                    RootfsDownloader.downloadFile(abiUrls.talloc, tallocFile,
                        (downloaded, total) -> {
                            int progress = (fileIndex * 100 + (int)(downloaded * 100 / total)) / totalFiles;
                            new Handler(Looper.getMainLooper()).post(() -> publishProgress(progress));
                        });
                }
                completedFiles++;
                
                // Download rootfs
                File rootfsFile = new File(rootfsDir, rootfsFileName);
                String rootfsUrl;
                if (type == TYPE_CUSTOM && customUrl != null) {
                    rootfsUrl = customUrl;
                } else {
                    // Determine distro type for URL selection
                    String distroType = "ALPINE"; // default
                    switch (type) {
                        case TYPE_UBUNTU:
                            distroType = "UBUNTU";
                            break;
                        case TYPE_UBUNTU22:
                            distroType = "UBUNTU22";
                            break;
                        case TYPE_KALI:
                            distroType = "KALI";
                            break;
                        case TYPE_ALPINE:
                        default:
                            distroType = "ALPINE";
                            break;
                    }
                    rootfsUrl = abiUrls.getRootfsUrl(distroType);
                }
                
                publishProgress(completedFiles * 100 / totalFiles);
                final int fileIndex = completedFiles;
                RootfsDownloader.downloadRootfs(rootfsUrl, rootfsFile,
                    (downloaded, total) -> {
                        int progress = (fileIndex * 100 + (int)(downloaded * 100 / total)) / totalFiles;
                        new Handler(Looper.getMainLooper()).post(() -> publishProgress(progress));
                    });
                completedFiles++;
                
                // Mark as installed
                String displayName = rootfsNameEdit.getText().toString().trim();
                if (displayName.isEmpty()) {
                    displayName = null;
                }
                rootfsManager.markRootfsInstalled(rootfsFileName, displayName);
                rootfsManager.setRootfsFileForWorkingMode(workingMode, rootfsFileName);
                
                // Set distro type based on filename and type
                String distroType = null;
                String lowerFileName = rootfsFileName.toLowerCase();
                switch (type) {
                    case TYPE_ALPINE:
                        distroType = "ALPINE";
                        break;
                    case TYPE_UBUNTU:
                        distroType = "UBUNTU";
                        break;
                    case TYPE_UBUNTU22:
                        distroType = "UBUNTU";
                        break; // Both Ubuntu versions use UBUNTU mode
                    case TYPE_KALI:
                        distroType = "KALI";
                        break;
                    default:
                        // Fallback based on filename
                        if (lowerFileName.contains("ubuntu22") || lowerFileName.contains("ubuntu-22")) {
                            distroType = "UBUNTU";
                        } else if (lowerFileName.contains("ubuntu")) {
                            distroType = "UBUNTU";
                        } else if (lowerFileName.contains("debian")) {
                            distroType = "DEBIAN";
                        } else if (lowerFileName.contains("kali")) {
                            distroType = "KALI";
                        } else if (lowerFileName.contains("arch")) {
                            distroType = "ARCH";
                        } else if (lowerFileName.contains("alpine")) {
                            distroType = "ALPINE";
                        }
                        break;
                }
                
                if (distroType != null) {
                    rootfsManager.setRootfsDistroType(rootfsFileName, distroType);
                }
                
                publishProgress(100);
                return true;
                
            } catch (Exception e) {
                errorMessage = e.getMessage();
                return false;
            }
        }
        
        @Override
        protected void onProgressUpdate(Integer... values) {
            if (values.length > 0) {
                progressBar.setProgress(values[0]);
                statusText.setText("Downloading... " + values[0] + "%");
            }
        }
        
        @Override
        protected void onPostExecute(Boolean success) {
            progressBar.setVisibility(View.GONE);
            installButton.setEnabled(true);
            
            if (success) {
                statusText.setText("Installation complete!");
                Toast.makeText(RootfsSetupActivity.this, "Rootfs installed successfully", Toast.LENGTH_SHORT).show();
                
                // Wait a moment then finish setup
                rootfsTypeGroup.postDelayed(() -> finishSetup(), 1000);
            } else {
                statusText.setText("Installation failed");
                new AlertDialog.Builder(RootfsSetupActivity.this)
                    .setTitle("Installation Failed")
                    .setMessage("Failed to install rootfs: " + (errorMessage != null ? errorMessage : "Unknown error"))
                    .setPositiveButton("OK", null)
                    .show();
            }
        }
    }
    
    private void finishSetup() {
        Intent intent = new Intent(this, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}

