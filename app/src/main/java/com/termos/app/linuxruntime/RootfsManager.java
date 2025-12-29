package com.termos.app.linuxruntime;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manages Linux rootfs files and installation state.
 * Ported from aterm's Rootfs.kt
 */
public class RootfsManager {
    private static final String PREFS_NAME = "termos_rootfs";
    private static final String KEY_INSTALLED_ROOTFS = "installed_rootfs";
    private static final String KEY_ROOTFS_NAME_PREFIX = "rootfs_name_";
    private static final String KEY_ROOTFS_FILE_MODE_PREFIX = "rootfs_file_mode_";
    private static final String KEY_ROOTFS_DISTRO_PREFIX = "rootfs_distro_";
    private static final String KEY_ROOTFS_INIT_PREFIX = "rootfs_init_";
    
    private final Context context;
    private final File rootfsDir;
    private final SharedPreferences prefs;
    
    public RootfsManager(Context context) {
        this.context = context;
        this.rootfsDir = context.getFilesDir();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ensureRootfsDir();
    }
    
    private void ensureRootfsDir() {
        if (!rootfsDir.exists()) {
            rootfsDir.mkdirs();
        }
    }
    
    /**
     * Check if rootfs files are downloaded and installed
     */
    public boolean isRootfsInstalled() {
        File proot = new File(rootfsDir, "proot");
        File libtalloc = new File(rootfsDir, "libtalloc.so.2");
        List<String> installedRootfs = getInstalledRootfs();
        
        return rootfsDir.exists() && 
               proot.exists() && 
               libtalloc.exists() && 
               !installedRootfs.isEmpty();
    }
    
    /**
     * Get list of installed rootfs files from disk
     */
    public List<String> getInstalledRootfs() {
        if (!rootfsDir.exists()) {
            return new ArrayList<>();
        }
        
        File[] files = rootfsDir.listFiles();
        if (files == null) {
            return new ArrayList<>();
        }
        
        List<String> result = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && (file.getName().endsWith(".tar.gz") || file.getName().endsWith(".tar"))) {
                result.add(file.getName());
            }
        }
        return result;
    }
    
    /**
     * Get list of installed rootfs from preferences
     */
    public List<String> getInstalledRootfsList() {
        String stored = prefs.getString(KEY_INSTALLED_ROOTFS, "");
        if (stored == null || stored.isEmpty()) {
            // Fallback: check files on disk
            return getInstalledRootfs();
        }
        
        List<String> result = new ArrayList<>();
        for (String name : stored.split(",")) {
            if (!name.trim().isEmpty()) {
                result.add(name.trim());
            }
        }
        return result;
    }
    
    /**
     * Check if a specific rootfs is installed
     */
    public boolean isRootfsInstalled(String rootfsName) {
        return new File(rootfsDir, rootfsName).exists();
    }
    
    /**
     * Mark a rootfs as installed
     */
    public void markRootfsInstalled(String rootfsName, String displayName) {
        List<String> installed = new ArrayList<>(getInstalledRootfsList());
        if (!installed.contains(rootfsName)) {
            installed.add(rootfsName);
            prefs.edit()
                .putString(KEY_INSTALLED_ROOTFS, String.join(",", installed))
                .apply();
        }
        
        if (displayName != null && !displayName.isEmpty()) {
            setRootfsDisplayName(rootfsName, displayName);
        }
    }
    
    /**
     * Get display name for a rootfs
     */
    public String getRootfsDisplayName(String rootfsName) {
        String nameKey = KEY_ROOTFS_NAME_PREFIX + rootfsName;
        String storedName = prefs.getString(nameKey, "");
        if (storedName != null && !storedName.isEmpty()) {
            return storedName;
        }
        
        // Default names
        if ("alpine.tar.gz".equals(rootfsName)) {
            return "Alpine";
        } else if ("ubuntu.tar.gz".equals(rootfsName)) {
            return "Ubuntu 20.04";
        } else if ("ubuntu22.tar.gz".equals(rootfsName)) {
            return "Ubuntu 22.04";
        } else if ("kali.tar.gz".equals(rootfsName)) {
            return "Kali Linux";
        } else {
            // Generate from filename
            String name = rootfsName.substring(0, rootfsName.lastIndexOf('.'));
            name = name.replace("_", " ");
            String[] words = name.split(" ");
            StringBuilder result = new StringBuilder();
            for (String word : words) {
                if (word.length() > 0) {
                    if (result.length() > 0) result.append(" ");
                    result.append(Character.toUpperCase(word.charAt(0)));
                    if (word.length() > 1) {
                        result.append(word.substring(1));
                    }
                }
            }
            return result.toString();
        }
    }
    
    /**
     * Set display name for a rootfs
     */
    public void setRootfsDisplayName(String rootfsName, String displayName) {
        String nameKey = KEY_ROOTFS_NAME_PREFIX + rootfsName;
        prefs.edit().putString(nameKey, displayName).apply();
    }
    
    /**
     * Get rootfs directory
     */
    public File getRootfsDir() {
        return rootfsDir;
    }
    
    /**
     * Get rootfs file path
     */
    public File getRootfsFile(String rootfsName) {
        return new File(rootfsDir, rootfsName);
    }
    
    /**
     * Get rootfs file name for a working mode
     */
    public String getRootfsFileName(int workingMode) {
        // Check if there's a stored mapping for this working mode
        String modeKey = KEY_ROOTFS_FILE_MODE_PREFIX + workingMode;
        String storedFile = prefs.getString(modeKey, "");
        if (storedFile != null && !storedFile.isEmpty() && isRootfsInstalled(storedFile)) {
            return storedFile;
        }
        
        // Fall back to default mappings
        switch (workingMode) {
            case 2: // UBUNTU
                return "ubuntu.tar.gz";
            case 0: // ALPINE
            default:
                List<String> installed = getInstalledRootfs();
                return installed.isEmpty() ? "alpine.tar.gz" : installed.get(0);
        }
    }
    
    /**
     * Set rootfs file for a working mode
     */
    public void setRootfsFileForWorkingMode(int workingMode, String rootfsFileName) {
        String modeKey = KEY_ROOTFS_FILE_MODE_PREFIX + workingMode;
        prefs.edit().putString(modeKey, rootfsFileName).apply();
    }
    
    /**
     * Get distro type for a rootfs
     */
    public String getRootfsDistroType(String rootfsName) {
        String distroKey = KEY_ROOTFS_DISTRO_PREFIX + rootfsName;
        return prefs.getString(distroKey, "");
    }
    
    /**
     * Set distro type for a rootfs
     */
    public void setRootfsDistroType(String rootfsName, String distroType) {
        String distroKey = KEY_ROOTFS_DISTRO_PREFIX + rootfsName;
        prefs.edit().putString(distroKey, distroType).apply();
    }
    
    /**
     * Get custom init script for a rootfs
     */
    public String getRootfsInitScript(String rootfsName) {
        String initKey = KEY_ROOTFS_INIT_PREFIX + rootfsName;
        return prefs.getString(initKey, "");
    }
    
    /**
     * Set custom init script for a rootfs
     */
    public void setRootfsInitScript(String rootfsName, String initScript) {
        String initKey = KEY_ROOTFS_INIT_PREFIX + rootfsName;
        prefs.edit().putString(initKey, initScript).apply();
    }
}

