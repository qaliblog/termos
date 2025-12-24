package com.termos.app.linuxruntime;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import java.io.*;

/**
 * Manages Linux runtime setup and maintenance.
 * Handles installation of required software via termos-setup.sh script.
 */
public class LinuxRuntimeManager {
    private static final String TAG = "LinuxRuntimeManager";
    private static final String SETUP_SCRIPT_NAME = "termos-setup.sh";
    private static final String SETUP_SCRIPT_PATH = "/usr/local/bin/termos-setup.sh";
    private static final String SETUP_MARKER = "/root/.termos-setup-complete";
    private static final String VNC_START_SCRIPT_NAME = "start-vnc.sh";
    private static final String VNC_START_SCRIPT_PATH = "/usr/local/bin/start-vnc.sh";
    private static final String INSTALL_LOMIRI_SCRIPT_NAME = "install-lomiri.sh";
    private static final String MOUNT_PROC_SCRIPT_NAME = "mount-proc.sh";
    private static final String DIAGNOSE_DESKTOP_SCRIPT_NAME = "diagnose-desktop.sh";
    
    private static LinuxRuntimeManager instance;
    private Context context;
    private RootfsManager rootfsManager;
    
    private LinuxRuntimeManager(Context context) {
        this.context = context.getApplicationContext();
        this.rootfsManager = new RootfsManager(context);
    }
    
    public static synchronized LinuxRuntimeManager getInstance(Context context) {
        if (instance == null) {
            instance = new LinuxRuntimeManager(context);
        }
        return instance;
    }
    
    /**
     * Ensure setup script is copied to rootfs and ready to run.
     * This should be called before first Linux session creation.
     */
    public void ensureSetupScriptReady() {
        if (!rootfsManager.isRootfsInstalled()) {
            Log.d(TAG, "Rootfs not installed yet, skipping setup script preparation");
            return;
        }
        
        // Copy setup script to rootfs if not present
        copyScriptToRootfs(SETUP_SCRIPT_NAME, SETUP_SCRIPT_PATH);
        
        // Copy VNC start script to rootfs if not present
        copyScriptToRootfs(VNC_START_SCRIPT_NAME, VNC_START_SCRIPT_PATH);
        
        // Copy helper scripts to rootfs (accessible from PRoot via $PREFIX/local/bin)
        copyScriptToRootfs(INSTALL_LOMIRI_SCRIPT_NAME, "$PREFIX/local/bin/" + INSTALL_LOMIRI_SCRIPT_NAME);
        copyScriptToRootfs(MOUNT_PROC_SCRIPT_NAME, "$PREFIX/local/bin/" + MOUNT_PROC_SCRIPT_NAME);
        copyScriptToRootfs(DIAGNOSE_DESKTOP_SCRIPT_NAME, "$PREFIX/local/bin/" + DIAGNOSE_DESKTOP_SCRIPT_NAME);
    }
    
    /**
     * Check if setup has been completed.
     * This checks for the marker file in the rootfs.
     */
    public boolean isSetupComplete() {
        if (!rootfsManager.isRootfsInstalled()) {
            return false;
        }
        
        // Check if marker file exists in rootfs
        // Note: This is a simplified check - in practice, you'd need to check
        // inside the rootfs directory structure
        File filesDir = context.getFilesDir();
        File localDir = new File(filesDir.getParentFile(), "local");
        
        // Try to detect rootfs directory
        String[] possibleRootfsDirs = {"alpine", "ubuntu"};
        for (String rootfsDir : possibleRootfsDirs) {
            File rootfsPath = new File(localDir, rootfsDir);
            File markerFile = new File(rootfsPath, SETUP_MARKER.substring(1)); // Remove leading /
            if (markerFile.exists()) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Copy a script from assets to the rootfs.
     * If targetPath contains $PREFIX, it will be copied to the local/bin directory
     * which is accessible from PRoot environment.
     */
    private void copyScriptToRootfs(String assetName, String targetPath) {
        try {
            File filesDir = context.getFilesDir();
            File localDir = new File(filesDir.getParentFile(), "local");
            File localBinDir = new File(localDir, "bin");
            localBinDir.mkdirs();
            
            // Extract target path components
            String targetFileName = new File(targetPath).getName();
            File targetFile = new File(localBinDir, targetFileName);
            
            // Skip if already exists
            if (targetFile.exists()) {
                Log.d(TAG, "Script already exists: " + targetFile.getAbsolutePath());
                return;
            }
            
            // Copy from assets
            AssetManager assets = context.getAssets();
            InputStream in = null;
            try {
                in = assets.open(assetName);
            } catch (IOException e) {
                // Script might not exist in assets, that's OK
                Log.d(TAG, "Script " + assetName + " not found in assets, skipping");
                return;
            }
            
            OutputStream out = new FileOutputStream(targetFile);
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            
            in.close();
            out.close();
            
            // Make executable
            targetFile.setExecutable(true, false);
            
            Log.d(TAG, "Copied script to: " + targetFile.getAbsolutePath());
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy script " + assetName + " to rootfs", e);
        }
    }
    
    /**
     * Get instructions for running the setup script.
     * Returns a command that can be executed in a Linux session.
     */
    public String getSetupCommand(boolean force) {
        String command = SETUP_SCRIPT_PATH;
        if (force) {
            command += " --force";
        }
        return command;
    }
    
    /**
     * Get command to start VNC server.
     */
    public String getVNCStartCommand() {
        return VNC_START_SCRIPT_PATH;
    }
    
    /**
     * Get RootfsManager instance.
     */
    public RootfsManager getRootfsManager() {
        return rootfsManager;
    }
}

