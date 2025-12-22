package com.termos.app.linuxruntime;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import com.termos.BuildConfig;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates Linux terminal sessions using proot-based rootfs.
 * Ported from aterm's MkSession.kt
 */
public class LinuxSessionCreator {
    
    private static final int WORKING_MODE_ALPINE = 0;
    private static final int WORKING_MODE_UBUNTU = 2;
    
    /**
     * Create a new terminal session with proot-based Linux rootfs
     */
    public static TerminalSession createSession(
            Context context,
            TerminalSessionClient sessionClient,
            String sessionId,
            int workingMode) {
        
        RootfsManager rootfsManager = new RootfsManager(context);
        File filesDir = context.getFilesDir();
        File localDir = new File(filesDir.getParentFile(), "local");
        File localBinDir = new File(localDir, "bin");
        File localLibDir = new File(localDir, "lib");
        
        // Ensure directories exist
        localBinDir.mkdirs();
        localLibDir.mkdirs();
        
        // Get rootfs filename for current working mode
        String rootfsFileName = rootfsManager.getRootfsFileName(workingMode);
        
        // Determine rootfs directory name
        String rootfsDirName;
        if ("ubuntu.tar.gz".equals(rootfsFileName)) {
            rootfsDirName = "ubuntu";
        } else if ("alpine.tar.gz".equals(rootfsFileName)) {
            rootfsDirName = "alpine";
        } else {
            // Custom rootfs - use filename without extension
            int lastDot = rootfsFileName.lastIndexOf('.');
            if (lastDot > 0) {
                rootfsDirName = rootfsFileName.substring(0, lastDot).toLowerCase().replace(" ", "_");
            } else {
                rootfsDirName = "alpine"; // fallback
            }
        }
        
        // Setup init script
        String initFileName = (workingMode == WORKING_MODE_UBUNTU) ? "init-host-ubuntu.sh" : "init-host.sh";
        File initFile = new File(localBinDir, initFileName.replace(".sh", ""));
        
        if (!initFile.exists()) {
            try {
                initFile.createNewFile();
                AssetManager assets = context.getAssets();
                InputStream initStream = assets.open(initFileName);
                OutputStream outStream = new FileOutputStream(initFile);
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = initStream.read(buffer)) != -1) {
                    outStream.write(buffer, 0, bytesRead);
                }
                initStream.close();
                outStream.close();
                initFile.setExecutable(true, false);
            } catch (IOException e) {
                // If init-host script not found, try init-host.sh
                try {
                    InputStream initStream = context.getAssets().open("init-host.sh");
                    OutputStream outStream = new FileOutputStream(initFile);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = initStream.read(buffer)) != -1) {
                        outStream.write(buffer, 0, bytesRead);
                    }
                    initStream.close();
                    outStream.close();
                    initFile.setExecutable(true, false);
                } catch (IOException e2) {
                    // Will use system shell if init scripts not available
                }
            }
        }
        
        // Setup init script inside Linux (runs after proot starts)
        File initScriptFile = new File(localBinDir, "init");
        String distroType = rootfsManager.getRootfsDistroType(rootfsFileName);
        
        // Auto-detect and set distro type if not set (for existing installations)
        if (distroType == null || distroType.isEmpty()) {
            String lowerFileName = rootfsFileName.toLowerCase();
            if (lowerFileName.contains("ubuntu")) {
                distroType = "UBUNTU";
                rootfsManager.setRootfsDistroType(rootfsFileName, distroType);
            } else if (lowerFileName.contains("debian")) {
                distroType = "DEBIAN";
                rootfsManager.setRootfsDistroType(rootfsFileName, distroType);
            } else if (lowerFileName.contains("kali")) {
                distroType = "KALI";
                rootfsManager.setRootfsDistroType(rootfsFileName, distroType);
            } else if (lowerFileName.contains("arch")) {
                distroType = "ARCH";
                rootfsManager.setRootfsDistroType(rootfsFileName, distroType);
            } else if (lowerFileName.contains("alpine")) {
                distroType = "ALPINE";
                rootfsManager.setRootfsDistroType(rootfsFileName, distroType);
            }
        }
        
        String customInitScript = rootfsManager.getRootfsInitScript(rootfsFileName);
        
        String initScriptName;
        if (customInitScript != null && !customInitScript.isEmpty()) {
            // Use custom init script
            try {
                FileWriter writer = new FileWriter(initScriptFile);
                writer.write(customInitScript);
                writer.close();
                initScriptFile.setExecutable(true, false);
            } catch (IOException e) {
                // Fallback to default
                initScriptName = "init.sh";
            }
        } else {
            // Determine which init script to use
            // First check distro type, then filename, then working mode
            if ("UBUNTU".equals(distroType)) {
                initScriptName = "init-ubuntu.sh";
            } else if ("DEBIAN".equals(distroType)) {
                initScriptName = "init-debian.sh";
            } else if ("KALI".equals(distroType)) {
                initScriptName = "init-kali.sh";
            } else if ("ARCH".equals(distroType)) {
                initScriptName = "init-arch.sh";
            } else if ("ubuntu.tar.gz".equals(rootfsFileName) || rootfsFileName.toLowerCase().contains("ubuntu")) {
                // Detect Ubuntu from filename if distro type not set
                initScriptName = "init-ubuntu.sh";
            } else if (rootfsFileName.toLowerCase().contains("debian")) {
                initScriptName = "init-debian.sh";
            } else if (rootfsFileName.toLowerCase().contains("kali")) {
                initScriptName = "init-kali.sh";
            } else if (rootfsFileName.toLowerCase().contains("arch")) {
                initScriptName = "init-arch.sh";
            } else {
                // Fallback to working mode or default to Alpine
                initScriptName = (workingMode == WORKING_MODE_UBUNTU) ? "init-ubuntu.sh" : "init.sh";
            }
            
            try {
                AssetManager assets = context.getAssets();
                InputStream initStream = assets.open(initScriptName);
                OutputStream outStream = new FileOutputStream(initScriptFile);
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = initStream.read(buffer)) != -1) {
                    outStream.write(buffer, 0, bytesRead);
                }
                initStream.close();
                outStream.close();
                initScriptFile.setExecutable(true, false);
            } catch (IOException e) {
                // Fallback to init.sh
                try {
                    AssetManager assets = context.getAssets();
                    InputStream initStream = assets.open("init.sh");
                    OutputStream outStream = new FileOutputStream(initScriptFile);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = initStream.read(buffer)) != -1) {
                        outStream.write(buffer, 0, bytesRead);
                    }
                    initStream.close();
                    outStream.close();
                    initScriptFile.setExecutable(true, false);
                } catch (IOException e2) {
                    // Will fail gracefully if no init script
                }
            }
        }
        
        // Build environment variables
        List<String> env = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path == null) path = "";
        env.add("PATH=" + path + ":/sbin:" + localBinDir.getAbsolutePath());
        env.add("HOME=/sdcard");
        
        File externalFilesDir = context.getExternalFilesDir(null);
        if (externalFilesDir != null) {
            env.add("PUBLIC_HOME=" + externalFilesDir.getAbsolutePath());
        }
        
        env.add("COLORTERM=truecolor");
        env.add("TERM=xterm-256color");
        env.add("LANG=C.UTF-8");
        env.add("BIN=" + localBinDir.getAbsolutePath());
        env.add("DEBUG=" + (BuildConfig.DEBUG ? "1" : "0"));
        env.add("PREFIX=" + filesDir.getParentFile().getAbsolutePath());
        env.add("LD_LIBRARY_PATH=" + localLibDir.getAbsolutePath());
        
        // Determine linker
        File linker64 = new File("/system/bin/linker64");
        String linker = linker64.exists() ? "/system/bin/linker64" : "/system/bin/linker";
        env.add("LINKER=" + linker);
        
        ApplicationInfo appInfo = context.getApplicationInfo();
        env.add("NATIVE_LIB_DIR=" + appInfo.nativeLibraryDir);
        env.add("PKG=" + context.getPackageName());
        env.add("RISH_APPLICATION_ID=" + context.getPackageName());
        env.add("PKG_PATH=" + appInfo.sourceDir);
        
        // PROOT_TMP_DIR
        File tempDir = new File(context.getCacheDir(), "termos_temp");
        File sessionTempDir = new File(tempDir, sessionId);
        sessionTempDir.mkdirs();
        env.add("PROOT_TMP_DIR=" + sessionTempDir.getAbsolutePath());
        env.add("TMPDIR=" + tempDir.getAbsolutePath());
        
        env.add("ROOTFS_FILE=" + rootfsFileName);
        env.add("ROOTFS_DIR=" + rootfsDirName);
        env.add("WORKING_MODE=" + workingMode);
        
        // PROOT_LOADER
        File prootLoader32 = new File(appInfo.nativeLibraryDir, "libproot-loader32.so");
        File prootLoader = new File(appInfo.nativeLibraryDir, "libproot-loader.so");
        
        if (prootLoader32.exists()) {
            env.add("PROOT_LOADER32=" + prootLoader32.getAbsolutePath());
        }
        if (prootLoader.exists()) {
            env.add("PROOT_LOADER=" + prootLoader.getAbsolutePath());
        }
        
        // Add Android environment variables
        addEnvIfNotNull(env, "ANDROID_ART_ROOT");
        addEnvIfNotNull(env, "ANDROID_DATA");
        addEnvIfNotNull(env, "ANDROID_I18N_ROOT");
        addEnvIfNotNull(env, "ANDROID_ROOT");
        addEnvIfNotNull(env, "ANDROID_RUNTIME_ROOT");
        addEnvIfNotNull(env, "ANDROID_TZDATA_ROOT");
        addEnvIfNotNull(env, "BOOTCLASSPATH");
        addEnvIfNotNull(env, "DEX2OATBOOTCLASSPATH");
        addEnvIfNotNull(env, "EXTERNAL_STORAGE");
        
        // Working directory - default to home in rootfs
        String workingDir = new File(localDir, rootfsDirName).getAbsolutePath() + "/root";
        File workingDirFile = new File(workingDir);
        if (!workingDirFile.exists()) {
            workingDirFile.mkdirs();
        }
        
        // Shell and args
        String shell = "/system/bin/sh";
        String[] args;
        
        if (initFile.exists()) {
            args = new String[]{"-c", initFile.getAbsolutePath()};
        } else {
            args = new String[0];
        }
        
        return new TerminalSession(
            shell,
            workingDir,
            args,
            env.toArray(new String[0]),
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            sessionClient
        );
    }
    
    private static void addEnvIfNotNull(List<String> env, String key) {
        String value = System.getenv(key);
        if (value != null) {
            env.add(key + "=" + value);
        }
    }
}

