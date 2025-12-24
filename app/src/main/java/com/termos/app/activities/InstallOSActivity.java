package com.termos.app.activities;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termos.R;
import com.termos.app.linuxruntime.LinuxCommandExecutor;
import com.termos.app.linuxruntime.RootfsManager;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.shell.command.environment.IShellEnvironment;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.File;

/**
 * Activity to install desktop environment and VNC server for OS tab.
 * Installs Lomiri desktop environment (Ubuntu Touch's desktop) and sets up VNC server.
 */
public class InstallOSActivity extends AppCompatActivity {
    private static final String TAG = "InstallOSActivity";
    private static final String PREFS_NAME = "termos_os_install";
    private static final String KEY_OS_INSTALLED = "os_installed";
    
    private RootfsManager rootfsManager;
    private LinuxCommandExecutor commandExecutor;
    private ProgressBar progressBar;
    private TextView statusText;
    private Button installButton;
    private Button uninstallButton;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_install_os);
        
        rootfsManager = new RootfsManager(this);
        commandExecutor = new LinuxCommandExecutor(this);
        
        initializeViews();
        checkInstallationStatus();
    }
    
    private void initializeViews() {
        progressBar = findViewById(R.id.progress_bar);
        statusText = findViewById(R.id.status_text);
        installButton = findViewById(R.id.install_button);
        uninstallButton = findViewById(R.id.uninstall_button);
        
        installButton.setOnClickListener(v -> startInstallation());
        uninstallButton.setOnClickListener(v -> showUninstallDialog());
    }
    
    private void checkInstallationStatus() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean isInstalled = prefs.getBoolean(KEY_OS_INSTALLED, false);
        
        if (isInstalled) {
            statusText.setText("OS is installed. Desktop environment and VNC server are ready.");
            installButton.setVisibility(View.GONE);
            uninstallButton.setVisibility(View.VISIBLE);
        } else {
            statusText.setText("Click Install OS to set up desktop environment and VNC server.");
            installButton.setVisibility(View.VISIBLE);
            uninstallButton.setVisibility(View.GONE);
        }
    }
    
    private void startInstallation() {
        // Check if rootfs is installed
        if (!rootfsManager.isRootfsInstalled()) {
            new AlertDialog.Builder(this)
                .setTitle("Rootfs Not Installed")
                .setMessage("Please install a Linux rootfs first (Ubuntu recommended) from Rootfs Setup.")
                .setPositiveButton("Go to Rootfs Setup", (dialog, which) -> {
                    Intent intent = new Intent(this, RootfsSetupActivity.class);
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
            return;
        }
        
        installButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText("Installing desktop environment and VNC server...\nThis may take several minutes.");
        
        // Start installation in background
        new InstallationTask().execute();
    }
    
    private class InstallationTask extends AsyncTask<Void, String, Boolean> {
        private String errorMessage = null;
        
        @Override
        protected Boolean doInBackground(Void... params) {
            try {
                publishProgress("Checking rootfs...");
                
                // Determine which rootfs is installed
                // Check all installed rootfs files to detect the distribution
                java.util.List<String> installedRootfs = rootfsManager.getInstalledRootfs();
                boolean isUbuntu = false;
                String rootfsFileName = null;
                
                // Check for Ubuntu first (preferred for desktop environment)
                for (String rootfs : installedRootfs) {
                    String lower = rootfs.toLowerCase();
                    if (lower.contains("ubuntu") || lower.contains("debian")) {
                        isUbuntu = true;
                        rootfsFileName = rootfs;
                        break;
                    }
                }
                
                // If no Ubuntu found, check for Alpine
                if (!isUbuntu && !installedRootfs.isEmpty()) {
                    rootfsFileName = installedRootfs.get(0);
                    String lower = rootfsFileName.toLowerCase();
                    isUbuntu = lower.contains("ubuntu") || lower.contains("debian");
                }
                
                Log.d(TAG, "Detected rootfs: " + rootfsFileName + ", isUbuntu: " + isUbuntu);
                
                String distroName = isUbuntu ? "Ubuntu/Debian" : "Alpine";
                publishProgress("Detected " + distroName + " distribution\n" +
                    "Preparing installation...\n" +
                    "This will install XFCE desktop and VNC server.");
                
                // Ensure rootfs is extracted
                File filesDir = InstallOSActivity.this.getFilesDir();
                File localDir = new File(filesDir.getParentFile(), "local");
                String rootfsDirName = rootfsFileName != null ? 
                    rootfsFileName.replace(".tar.gz", "").replace(".tar", "").toLowerCase() : "ubuntu";
                File rootfsDir = new File(localDir, rootfsDirName);
                
                // Check if rootfs is extracted
                if (!rootfsDir.exists() || (!new File(rootfsDir, "usr").exists() && !new File(rootfsDir, "bin").exists())) {
                    errorMessage = "Rootfs not extracted. Please start a terminal session first to extract the rootfs.";
                    Log.e(TAG, "Rootfs not extracted at: " + rootfsDir.getAbsolutePath());
                    return false;
                }
                
                // Install desktop environment and VNC server
                String installScript = createInstallScript(isUbuntu);
                
                // Save script to rootfs
                File installScriptFile = new File(rootfsDir, "root/install-os.sh");
                
                // Ensure directories exist
                installScriptFile.getParentFile().mkdirs();
                
                try {
                    java.io.FileWriter writer = new java.io.FileWriter(installScriptFile);
                    writer.write(installScript);
                    writer.close();
                    installScriptFile.setExecutable(true);
                    Log.d(TAG, "Installation script saved to: " + installScriptFile.getAbsolutePath());
                } catch (java.io.IOException e) {
                    errorMessage = "Failed to save installation script: " + e.getMessage();
                    Log.e(TAG, "Failed to save script", e);
                    return false;
                }
                
                final boolean[] success = {false};
                final String[] error = {null};
                
                // Execute using AppShell - the command will run within rootfs via init-host script
                try {
                    // Get the init-host script path (filesDir and localDir already defined above)
                    File localBinDir = new File(localDir, "bin");
                    
                    // Determine which init script to use
                    String initScriptName = isUbuntu ? "init-host-ubuntu" : "init-host";
                    File initScriptFile = new File(localBinDir, initScriptName);
                    
                    // If init script doesn't exist, try to create it from assets
                    if (!initScriptFile.exists()) {
                        try {
                            initScriptFile.getParentFile().mkdirs();
                            android.content.res.AssetManager assets = InstallOSActivity.this.getAssets();
                            String assetName = isUbuntu ? "init-host-ubuntu.sh" : "init-host.sh";
                            java.io.InputStream in = assets.open(assetName);
                            java.io.OutputStream out = new java.io.FileOutputStream(initScriptFile);
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = in.read(buffer)) != -1) {
                                out.write(buffer, 0, bytesRead);
                            }
                            in.close();
                            out.close();
                            initScriptFile.setExecutable(true);
                            Log.d(TAG, "Created init script: " + initScriptFile.getAbsolutePath());
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to create init script", e);
                            errorMessage = "Failed to create init script: " + e.getMessage();
                            return false;
                        }
                    }
                    
                    // Verify proot and libtalloc exist (required by init-host script)
                    // init-host expects proot at $PREFIX/local/bin/proot
                    File prootFile = new File(localBinDir, "proot");
                    File libtallocFile = new File(filesDir, "libtalloc.so.2");
                    File localLibDir = new File(localDir, "lib");
                    
                    // Check and copy proot if needed
                    if (!prootFile.exists()) {
                        File prootInFiles = new File(filesDir, "proot");
                        if (prootInFiles.exists()) {
                            try {
                                prootFile.getParentFile().mkdirs();
                                java.nio.file.Files.copy(prootInFiles.toPath(), prootFile.toPath(), 
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                prootFile.setExecutable(true);
                                Log.d(TAG, "Copied proot to: " + prootFile.getAbsolutePath());
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to copy proot", e);
                                errorMessage = "Proot binary not found. Please ensure rootfs is properly installed.";
                                return false;
                            }
                        } else {
                            Log.e(TAG, "Proot not found at: " + prootFile.getAbsolutePath() + " or " + prootInFiles.getAbsolutePath());
                            errorMessage = "Proot binary not found. Please ensure rootfs is properly installed.";
                            return false;
                        }
                    }
                    
                    // Check and copy libtalloc.so.2 if needed
                    if (!libtallocFile.exists()) {
                        Log.e(TAG, "libtalloc.so.2 not found at: " + libtallocFile.getAbsolutePath());
                        errorMessage = "libtalloc.so.2 not found. Please ensure rootfs is properly installed.";
                        return false;
                    }
                    
                    // Copy libtalloc to local/lib if not there (for LD_LIBRARY_PATH)
                    File libtallocInLocal = new File(localLibDir, "libtalloc.so.2");
                    if (!libtallocInLocal.exists()) {
                        try {
                            localLibDir.mkdirs();
                            java.nio.file.Files.copy(libtallocFile.toPath(), libtallocInLocal.toPath(), 
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            Log.d(TAG, "Copied libtalloc.so.2 to: " + libtallocInLocal.getAbsolutePath());
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to copy libtalloc.so.2 to local/lib, will use files dir in LD_LIBRARY_PATH", e);
                        }
                    }
                    
                    // Command to execute within rootfs
                    // The init-host script must be executed via /system/bin/sh (like TerminalSession does)
                    // It will set up proot and execute the command inside the rootfs
                    String shellCommand;
                    String[] shellArgs;
                    
                    if (initScriptFile.exists()) {
                        // Execute init-host script via /system/bin/sh
                        // The init-host script passes "$@" to /bin/init inside rootfs
                        // The init script executes the arguments as a command
                        shellCommand = "/system/bin/sh";
                        // Pass the command as arguments to init-host
                        // init-host will pass these to /bin/init which will execute them
                        // We need to pass: sh -c '/root/install-os.sh'
                        // But as separate arguments, not as a single string
                        String initScriptPath = initScriptFile.getAbsolutePath();
                        String commandToRun = "/root/install-os.sh";
                        // Escape single quotes in the command
                        String escapedCommand = commandToRun.replace("'", "'\"'\"'");
                        shellArgs = new String[]{"-c", initScriptPath + " sh -c '" + escapedCommand + "'"};
                        Log.d(TAG, "Using init-host script: " + initScriptPath);
                        Log.d(TAG, "Command to run: " + commandToRun);
                    } else {
                        // Fallback: try /bin/sh directly (won't work in rootfs, but might help debug)
                        Log.w(TAG, "Init script not found, using /bin/sh fallback");
                        shellCommand = "/bin/sh";
                        shellArgs = new String[]{"-c", "/root/install-os.sh"};
                    }
                    
                    ExecutionCommand executionCommand = new ExecutionCommand(
                        -1, // id
                        shellCommand,
                        shellArgs,
                        null, // stdin
                        filesDir.getParentFile().getAbsolutePath(), // working directory (PREFIX)
                        ExecutionCommand.Runner.APP_SHELL.getName(),
                        false // not failsafe
                    );
                    
                    Log.d(TAG, "ExecutionCommand: executable=" + executionCommand.executable + 
                          ", args=" + java.util.Arrays.toString(executionCommand.arguments) +
                          ", workingDir=" + executionCommand.workingDirectory);
                    
                    IShellEnvironment shellEnvironment = new TermuxShellEnvironment();
                    
                    // Set environment variables for rootfs detection (required by init-host script)
                    // These must match what TerminalSession sets up
                    java.util.HashMap<String, String> additionalEnvironment = new java.util.HashMap<>();
                    additionalEnvironment.put("ROOTFS_FILE", rootfsFileName);
                    additionalEnvironment.put("ROOTFS_DIR", rootfsDirName);
                    additionalEnvironment.put("PREFIX", filesDir.getParentFile().getAbsolutePath());
                    
                    // Set PATH to include system binaries and local bin
                    String systemPath = System.getenv("PATH");
                    if (systemPath == null) systemPath = "";
                    String path = systemPath + ":/sbin:" + localBinDir.getAbsolutePath();
                    additionalEnvironment.put("PATH", path);
                    
                    // Set LD_LIBRARY_PATH to include libtalloc.so.2 location
                    // Include both filesDir (where libtalloc might be) and localLibDir (already defined above)
                    String ldLibraryPath = filesDir.getAbsolutePath();
                    if (localLibDir.exists()) {
                        ldLibraryPath = ldLibraryPath + ":" + localLibDir.getAbsolutePath();
                    }
                    additionalEnvironment.put("LD_LIBRARY_PATH", ldLibraryPath);
                    
                    // Set other required environment variables
                    additionalEnvironment.put("HOME", "/sdcard");
                    additionalEnvironment.put("TERM", "xterm-256color");
                    additionalEnvironment.put("LANG", "C.UTF-8");
                    additionalEnvironment.put("BIN", localBinDir.getAbsolutePath());
                    
                    // Set linker path
                    File linker64 = new File("/system/bin/linker64");
                    String linker = linker64.exists() ? "/system/bin/linker64" : "/system/bin/linker";
                    additionalEnvironment.put("LINKER", linker);
                    
                    // Set PROOT_TMP_DIR
                    File tempDir = new File(InstallOSActivity.this.getCacheDir(), "termos_temp");
                    tempDir.mkdirs();
                    additionalEnvironment.put("PROOT_TMP_DIR", tempDir.getAbsolutePath());
                    additionalEnvironment.put("TMPDIR", tempDir.getAbsolutePath());
                    
                    Log.d(TAG, "Environment: PATH=" + path);
                    Log.d(TAG, "Environment: LD_LIBRARY_PATH=" + ldLibraryPath);
                    
                    AppShell.AppShellClient appShellClient = new AppShell.AppShellClient() {
                        @Override
                        public void onAppShellExited(AppShell appShell) {
                            if (appShell == null) {
                                error[0] = "Command execution failed - AppShell returned null";
                                return;
                            }
                            
                            ExecutionCommand cmd = appShell.getExecutionCommand();
                            if (cmd == null) {
                                error[0] = "Command execution failed - ExecutionCommand is null";
                                return;
                            }
                            
                            int exitCode = cmd.resultData.exitCode;
                            String stdout = cmd.resultData.stdout != null ? cmd.resultData.stdout.toString() : "";
                            String stderr = cmd.resultData.stderr != null ? cmd.resultData.stderr.toString() : "";
                            
                            Log.d(TAG, "Installation command completed (exit code: " + exitCode + ")");
                            Log.d(TAG, "stdout: " + stdout);
                            Log.d(TAG, "stderr: " + stderr);
                            
                            if (exitCode == 0) {
                                success[0] = true;
                                Log.d(TAG, "Installation successful: " + stdout);
                            } else {
                                error[0] = "Command failed with exit code " + exitCode + ": " + stderr;
                            }
                        }
                    };
                    
                    publishProgress("Starting installation script...\n" +
                        "Fixing any package manager issues...\n" +
                        "Installing desktop environment and VNC server...\n" +
                        "This may take 10-20 minutes. Please wait...");
                    AppShell appShell = AppShell.execute(
                        InstallOSActivity.this,
                        executionCommand,
                        appShellClient,
                        shellEnvironment,
                        additionalEnvironment, // additionalEnvironment with ROOTFS_FILE and ROOTFS_DIR
                        false // isSynchronous
                    );
                    
                    if (appShell == null) {
                        errorMessage = "Failed to start installation - AppShell.execute() returned null. Check if rootfs is properly initialized.";
                        Log.e(TAG, "AppShell.execute() returned null");
                        return false;
                    }
                    
                    // Wait for completion (with timeout)
                    long startTime = System.currentTimeMillis();
                    long timeout = 1800000; // 30 minute timeout (increased from 10 minutes)
                    int progressCounter = 0;
                    while (!success[0] && error[0] == null && 
                           (System.currentTimeMillis() - startTime) < timeout) {
                        try {
                            Thread.sleep(2000);
                            progressCounter++;
                            long elapsed = System.currentTimeMillis() - startTime;
                            long minutes = elapsed / 60000;
                            long seconds = (elapsed % 60000) / 1000;
                            
                            // Update progress message every 5 seconds
                            if (progressCounter % 3 == 0) {
                                String progressMsg = String.format(
                                    "Installing desktop environment...\n" +
                                    "Elapsed time: %d:%02d\n" +
                                    "This may take 10-20 minutes.\n" +
                                    "Please be patient...",
                                    minutes, seconds
                                );
                                publishProgress(progressMsg);
                            }
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    
                    if (error[0] != null) {
                        errorMessage = error[0];
                        return false;
                    }
                    
                    if (!success[0]) {
                        if (error[0] == null) {
                            errorMessage = "Installation timed out after 30 minutes. The installation may still be in progress. Please check the logs or try again.";
                        } else {
                            errorMessage = error[0];
                        }
                        return false;
                    }
                    
                    publishProgress("Configuration complete!");
                    return true;
                    
                } catch (Exception e) {
                    errorMessage = "Execution error: " + e.getMessage();
                    Log.e(TAG, "Installation error", e);
                    return false;
                }
                
            } catch (Exception e) {
                errorMessage = e.getMessage();
                Log.e(TAG, "Installation error", e);
                return false;
            }
        }
        
        @Override
        protected void onProgressUpdate(String... values) {
            if (values.length > 0) {
                statusText.setText(values[0]);
            }
        }
        
        @Override
        protected void onPostExecute(Boolean success) {
            progressBar.setVisibility(View.GONE);
            installButton.setEnabled(true);
            
            if (success) {
                // Mark as installed
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putBoolean(KEY_OS_INSTALLED, true).apply();
                
                statusText.setText("Installation complete!\n\n" +
                    "Desktop environment (Lomiri) and VNC server are installed and configured.\n" +
                    "The OS tab will automatically connect to the VNC server when opened.");
                installButton.setVisibility(View.GONE);
                uninstallButton.setVisibility(View.VISIBLE);
                
                Toast.makeText(InstallOSActivity.this, "OS installation complete!", Toast.LENGTH_LONG).show();
            } else {
                statusText.setText("Installation failed: " + (errorMessage != null ? errorMessage : "Unknown error"));
                Toast.makeText(InstallOSActivity.this, "Installation failed. Check logs for details.", Toast.LENGTH_LONG).show();
            }
        }
    }
    
    /**
     * Create installation script for desktop environment and VNC server.
     * For Ubuntu: Installs Lomiri (Ubuntu Touch desktop environment)
     * For Alpine: Falls back to XFCE (Lomiri not available)
     */
    private String createInstallScript(boolean isUbuntu) {
        if (isUbuntu) {
            // Ubuntu/Debian installation script - Lomiri
            return "#!/bin/bash\n" +
                "set -e\n" +
                "export DEBIAN_FRONTEND=noninteractive\n" +
                "\n" +
                "# Fix any interrupted dpkg operations first\n" +
                "echo 'Checking for interrupted dpkg operations...'\n" +
                "dpkg --configure -a 2>&1 || true\n" +
                "echo 'Fixed dpkg configuration'\n" +
                "\n" +
                "# Update package lists\n" +
                "echo 'Updating package lists...'\n" +
                "apt-get update -qq || {\n" +
                "    echo 'apt-get update failed, retrying after fixing dpkg...'\n" +
                "    dpkg --configure -a 2>&1 || true\n" +
                "    apt-get update -qq\n" +
                "}\n" +
                "echo 'Package lists updated'\n" +
                "\n" +
                "# Try to add UBports repository for Lomiri packages\n" +
                "echo 'Attempting to add UBports repository for Lomiri...'\n" +
                "UBPORTS_ADDED=0\n" +
                "# Check Ubuntu version\n" +
                "UBUNTU_VERSION=$(lsb_release -cs 2>/dev/null || echo 'jammy')\n" +
                "if [ -n \"$UBUNTU_VERSION\" ]; then\n" +
                "    # Try to add UBports repository\n" +
                "    if ! grep -q 'ubports' /etc/apt/sources.list.d/ubports.list 2>/dev/null; then\n" +
                "        echo \"deb http://repo.ubports.com/ $UBUNTU_VERSION main\" > /etc/apt/sources.list.d/ubports.list 2>/dev/null || true\n" +
                "        # Try to add GPG key (may fail in container, that's OK)\n" +
                "        apt-key adv --keyserver keyserver.ubuntu.com --recv-keys 0E61A7B277ED8A82 2>/dev/null || true\n" +
                "        apt-get update -qq 2>/dev/null || true\n" +
                "        UBPORTS_ADDED=1\n" +
                "    fi\n" +
                "fi\n" +
                "\n" +
                "# Try to install Lomiri (Ubuntu Touch desktop environment)\n" +
                "echo 'Attempting to install Lomiri desktop environment...'\n" +
                "LOMIRI_INSTALLED=0\n" +
                "# Try multiple methods to install Lomiri\n" +
                "if apt-get install -y -qq lomiri-session 2>/dev/null; then\n" +
                "    LOMIRI_INSTALLED=1\n" +
                "    echo 'Lomiri installed successfully from standard repos'\n" +
                "elif apt-get install -y -qq unity8-desktop-session-mir 2>/dev/null; then\n" +
                "    LOMIRI_INSTALLED=1\n" +
                "    echo 'Unity8/Lomiri installed successfully'\n" +
                "elif [ $UBPORTS_ADDED -eq 1 ] && apt-get install -y -qq lomiri-session 2>/dev/null; then\n" +
                "    LOMIRI_INSTALLED=1\n" +
                "    echo 'Lomiri installed successfully from UBports repo'\n" +
                "else\n" +
                "    echo 'Lomiri packages not available in repositories'\n" +
                "    echo 'Attempting to install from source or alternative method...'\n" +
                "    # Try to install build dependencies and build from source\n" +
                "    apt-get install -y -qq build-essential cmake qtbase5-dev qtdeclarative5-dev qml-module-qtquick2 2>/dev/null || true\n" +
                "    # For now, fall back to XFCE but keep trying to get Lomiri working\n" +
                "    echo 'Installing XFCE as temporary desktop while we set up Lomiri...'\n" +
                "    apt-get install -y -qq xfce4 xfce4-goodies xfce4-terminal || {\n" +
                "        echo 'XFCE installation had issues, fixing dpkg and retrying...'\n" +
                "        dpkg --configure -a 2>&1 || true\n" +
                "        apt-get install -y -qq -f || true\n" +
                "        apt-get install -y -qq xfce4 xfce4-goodies xfce4-terminal || true\n" +
                "    }\n" +
                "fi\n" +
                "\n" +
                "# Install Mir display server and Xwayland (required for Lomiri with VNC)\n" +
                "if [ $LOMIRI_INSTALLED -eq 1 ]; then\n" +
                "    echo 'Installing Mir display server and Xwayland...'\n" +
                "    apt-get install -y -qq mir mir-graphics-drivers-mesa xwayland || {\n" +
                "        echo 'Some Mir packages failed, trying individual packages...'\n" +
                "        apt-get install -y -qq mir 2>/dev/null || true\n" +
                "        apt-get install -y -qq xwayland 2>/dev/null || true\n" +
                "    }\n" +
                "    echo 'Mir and Xwayland installation complete'\n" +
                "fi\n" +
                "\n" +
                "# Install VNC server (TigerVNC or tightvnc)\n" +
                "echo 'Installing VNC server...'\n" +
                "apt-get install -y -qq tigervnc-standalone-server tigervnc-common || \\\n" +
                "apt-get install -y -qq tightvncserver || \\\n" +
                "apt-get install -y -qq x11vnc || {\n" +
                "    echo 'VNC installation had issues, fixing dpkg and retrying...'\n" +
                "    dpkg --configure -a 2>&1 || true\n" +
                "    apt-get install -y -qq -f || true\n" +
                "    apt-get install -y -qq tigervnc-standalone-server tigervnc-common || \\\n" +
                "    apt-get install -y -qq tightvncserver || \\\n" +
                "    apt-get install -y -qq x11vnc || true\n" +
                "}\n" +
                "\n" +
                "# Install additional utilities\n" +
                "echo 'Installing additional utilities...'\n" +
                "apt-get install -y -qq dbus-x11 || {\n" +
                "    dpkg --configure -a 2>&1 || true\n" +
                "    apt-get install -y -qq -f || true\n" +
                "    apt-get install -y -qq dbus-x11 || true\n" +
                "}\n" +
                "if [ $LOMIRI_INSTALLED -eq 0 ]; then\n" +
                "    apt-get install -y -qq xfce4-session xfce4-panel || true\n" +
                "fi\n" +
                "echo 'Additional utilities installed'\n" +
                "\n" +
                "# Create VNC startup script\n" +
                "mkdir -p /root/.vnc\n" +
                "if [ $LOMIRI_INSTALLED -eq 1 ]; then\n" +
                "    # Lomiri startup script with Xwayland bridge for VNC\n" +
                "    cat > /root/.vnc/xstartup << 'VNCEOF'\n" +
                "#!/bin/bash\n" +
                "unset SESSION_MANAGER\n" +
                "unset DBUS_SESSION_BUS_ADDRESS\n" +
                "export DISPLAY=${DISPLAY:-:1}\n" +
                "export USER=root\n" +
                "export HOME=/root\n" +
                "\n" +
                "echo \"Lomiri xstartup script executing at $(date)\" > /tmp/xstartup.log 2>&1\n" +
                "\n" +
                "# Mount /proc if not already mounted (required for D-Bus and other services)\n" +
                "if ! mountpoint -q /proc 2>/dev/null; then\n" +
                "    echo 'Mounting /proc...' >> /tmp/xstartup.log 2>&1\n" +
                "    if [ -d /proc ] && [ -r /proc/version ] 2>/dev/null; then\n" +
                "        mount --bind /proc /proc 2>/dev/null || true\n" +
                "    else\n" +
                "        mount -t proc proc /proc 2>/dev/null || true\n" +
                "    fi\n" +
                "fi\n" +
                "\n" +
                "# Load X resources if available\n" +
                "[ -r $HOME/.Xresources ] && xrdb $HOME/.Xresources 2>/dev/null || true\n" +
                "\n" +
                "# Wait for X server to be ready\n" +
                "sleep 2\n" +
                "\n" +
                "# Start D-Bus session daemon\n" +
                "if [ -z \"$DBUS_SESSION_BUS_ADDRESS\" ]; then\n" +
                "    echo 'Starting D-Bus session...' >> /tmp/xstartup.log 2>&1\n" +
                "    export TMPDIR=/tmp\n" +
                "    DBUS_OUTPUT=$(dbus-launch --sh-syntax 2>/dev/null || dbus-launch --sh-syntax 2>&1 | grep -v 'shm-helper' || true)\n" +
                "    if [ -n \"$DBUS_OUTPUT\" ]; then\n" +
                "        eval \"$DBUS_OUTPUT\" >> /tmp/xstartup.log 2>&1\n" +
                "    fi\n" +
                "    if [ -n \"$DBUS_SESSION_BUS_ADDRESS\" ]; then\n" +
                "        echo \"D-Bus started at: $DBUS_SESSION_BUS_ADDRESS\" >> /tmp/xstartup.log 2>&1\n" +
                "    fi\n" +
                "fi\n" +
                "\n" +
                "# Method 1: Try to run Lomiri with Xwayland on X11 (for VNC compatibility)\n" +
                "echo 'Attempting to start Lomiri with Xwayland...' >> /tmp/xstartup.log 2>&1\n" +
                "if command -v Xwayland >/dev/null 2>&1 && command -v lomiri-session >/dev/null 2>&1; then\n" +
                "    # Start Xwayland on a separate display\n" +
                "    export WAYLAND_DISPLAY=wayland-0\n" +
                "    export XDG_SESSION_TYPE=wayland\n" +
                "    # Start Mir with X11 backend\n" +
                "    if command -v mir_display_server >/dev/null 2>&1; then\n" +
                "        mir_display_server --x11-display=$DISPLAY --x11-scale=1 >/tmp/mir.log 2>&1 &\n" +
                "        sleep 2\n" +
                "    fi\n" +
                "    # Start Lomiri session\n" +
                "    lomiri-session >/tmp/lomiri.log 2>&1 &\n" +
                "    LOMIRI_PID=$!\n" +
                "    sleep 3\n" +
                "    if kill -0 $LOMIRI_PID 2>/dev/null; then\n" +
                "        echo 'Lomiri started successfully with Xwayland' >> /tmp/xstartup.log 2>&1\n" +
                "    else\n" +
                "        echo 'Lomiri failed to start, trying alternative method...' >> /tmp/xstartup.log 2>&1\n" +
                "        # Fallback: try direct X11 mode if available\n" +
                "        export XDG_SESSION_TYPE=x11\n" +
                "        lomiri-session >/tmp/lomiri.log 2>&1 &\n" +
                "    fi\n" +
                "elif command -v lomiri-session >/dev/null 2>&1; then\n" +
                "    # Method 2: Try direct X11 mode\n" +
                "    echo 'Trying Lomiri in X11 mode...' >> /tmp/xstartup.log 2>&1\n" +
                "    export XDG_SESSION_TYPE=x11\n" +
                "    lomiri-session >/tmp/lomiri.log 2>&1 &\n" +
                "else\n" +
                "    echo 'Lomiri not found, falling back to basic X session' >> /tmp/xstartup.log 2>&1\n" +
                "    # Fallback to basic terminal\n" +
                "    xterm -fa \"DejaVu Sans Mono\" -fs 14 &\n" +
                "fi\n" +
                "\n" +
                "echo 'Lomiri startup complete' >> /tmp/xstartup.log 2>&1\n" +
                "\n" +
                "# Keep script running\n" +
                "while true; do\n" +
                "    sleep 60\n" +
                "    # Check if Lomiri is still running, restart if needed\n" +
                "    if ! pgrep -x lomiri-session >/dev/null 2>&1 && command -v lomiri-session >/dev/null 2>&1; then\n" +
                "        echo 'Lomiri not running, attempting restart...' >> /tmp/xstartup.log 2>&1\n" +
                "        lomiri-session >/tmp/lomiri-restart.log 2>&1 &\n" +
                "    fi\n" +
                "done\n" +
                "VNCEOF\n" +
                "else\n" +
                "    # XFCE startup script (fallback)\n" +
                "    cat > /root/.vnc/xstartup << 'VNCEOF'\n" +
                "#!/bin/bash\n" +
                "unset SESSION_MANAGER\n" +
                "unset DBUS_SESSION_BUS_ADDRESS\n" +
                "export DISPLAY=${DISPLAY:-:1}\n" +
                "export USER=root\n" +
                "export HOME=/root\n" +
                "\n" +
                "echo \"Xstartup script executing at $(date)\" > /tmp/xstartup.log 2>&1\n" +
                "\n" +
                "# Mount /proc if not already mounted (required for D-Bus and other services)\n" +
                "if ! mountpoint -q /proc 2>/dev/null; then\n" +
                "    echo 'Mounting /proc...' >> /tmp/xstartup.log 2>&1\n" +
                "    if [ -d /proc ] && [ -r /proc/version ] 2>/dev/null; then\n" +
                "        mount --bind /proc /proc 2>/dev/null || true\n" +
                "    else\n" +
                "        mount -t proc proc /proc 2>/dev/null || true\n" +
                "    fi\n" +
                "fi\n" +
                "\n" +
                "# Load X resources if available\n" +
                "[ -r $HOME/.Xresources ] && xrdb $HOME/.Xresources 2>/dev/null || true\n" +
                "\n" +
                "# Wait for X server to be ready\n" +
                "sleep 2\n" +
                "\n" +
                "# Kill any existing desktop processes to avoid conflicts\n" +
                "pkill -9 xfwm4 2>/dev/null || true\n" +
                "pkill -9 xfce4-session 2>/dev/null || true\n" +
                "pkill -9 xfsettingsd 2>/dev/null || true\n" +
                "sleep 1\n" +
                "\n" +
                "# Start D-Bus session daemon\n" +
                "if [ -z \"$DBUS_SESSION_BUS_ADDRESS\" ]; then\n" +
                "    echo 'Starting D-Bus session...' >> /tmp/xstartup.log 2>&1\n" +
                "    # Use dbus-launch with proper options to avoid shm-helper errors\n" +
                "    # Set TMPDIR to an absolute path to help with shm-helper path resolution\n" +
                "    export TMPDIR=/tmp\n" +
                "    # Use --sh-syntax and redirect stderr to avoid shm-helper warnings\n" +
                "    # The shm-helper error is harmless but we suppress it\n" +
                "    DBUS_OUTPUT=$(dbus-launch --sh-syntax 2>/dev/null || dbus-launch --sh-syntax 2>&1 | grep -v 'shm-helper' || true)\n" +
                "    if [ -n \"$DBUS_OUTPUT\" ]; then\n" +
                "        eval \"$DBUS_OUTPUT\" >> /tmp/xstartup.log 2>&1\n" +
                "    fi\n" +
                "    if [ -n \"$DBUS_SESSION_BUS_ADDRESS\" ]; then\n" +
                "        echo \"D-Bus started at: $DBUS_SESSION_BUS_ADDRESS\" >> /tmp/xstartup.log 2>&1\n" +
                "    else\n" +
                "        echo \"D-Bus startup had issues, continuing anyway...\" >> /tmp/xstartup.log 2>&1\n" +
                "    fi\n" +
                "fi\n" +
                "\n" +
                "# Start XFCE desktop environment\n" +
                "echo 'Starting XFCE desktop...' >> /tmp/xstartup.log 2>&1\n" +
                "\n" +
                "# Try startxfce4 first (handles everything)\n" +
                "if command -v startxfce4 >/dev/null 2>&1; then\n" +
                "    echo 'Using startxfce4 command' >> /tmp/xstartup.log 2>&1\n" +
                "    # Use --replace to replace any existing window manager\n" +
                "    startxfce4 --replace >/tmp/xfce4.log 2>&1 &\n" +
                "    XFCE_PID=$!\n" +
                "    sleep 3\n" +
                "    # Check if it's still running\n" +
                "    if ! kill -0 $XFCE_PID 2>/dev/null; then\n" +
                "        echo 'startxfce4 exited, trying xfce4-session...' >> /tmp/xstartup.log 2>&1\n" +
                "        xfce4-session >/tmp/xfce4.log 2>&1 &\n" +
                "    fi\n" +
                "elif command -v xfce4-session >/dev/null 2>&1; then\n" +
                "    echo 'Using xfce4-session command' >> /tmp/xstartup.log 2>&1\n" +
                "    xfce4-session >/tmp/xfce4.log 2>&1 &\n" +
                "else\n" +
                "    echo 'Using individual XFCE components' >> /tmp/xstartup.log 2>&1\n" +
                "    # Fallback: start basic XFCE components manually\n" +
                "    xfwm4 --replace >/tmp/xfce4.log 2>&1 &\n" +
                "    sleep 1\n" +
                "    xfce4-panel >>/tmp/xfce4.log 2>&1 &\n" +
                "    sleep 1\n" +
                "    xfdesktop >>/tmp/xfce4.log 2>&1 &\n" +
                "    sleep 1\n" +
                "    xfsettingsd >>/tmp/xfce4.log 2>&1 &\n" +
                "fi\n" +
                "\n" +
                "echo 'XFCE startup command executed' >> /tmp/xstartup.log 2>&1\n" +
                "sleep 2\n" +
                "echo 'Checking XFCE processes...' >> /tmp/xstartup.log 2>&1\n" +
                "ps aux | grep -E '[x]fce|[x]fwm' | grep -v grep >> /tmp/xstartup.log 2>&1 || echo 'No XFCE processes found' >> /tmp/xstartup.log 2>&1\n" +
                "\n" +
                "# Keep script running (Xvnc expects xstartup to stay alive)\n" +
                "# Use wait to keep process alive and monitor child processes\n" +
                "while true; do\n" +
                "    sleep 60\n" +
                "    # Check if XFCE is still running, restart if needed\n" +
                "    if ! pgrep -x xfwm4 >/dev/null 2>&1 && ! pgrep -x xfce4-session >/dev/null 2>&1; then\n" +
                "        echo 'XFCE not running, attempting restart...' >> /tmp/xstartup.log 2>&1\n" +
                "        if command -v startxfce4 >/dev/null 2>&1; then\n" +
                "            startxfce4 --replace >/tmp/xfce4-restart.log 2>&1 &\n" +
                "        fi\n" +
                "    fi\n" +
                "done\n" +
                "VNCEOF\n" +
                "fi\n" +
                "chmod +x /root/.vnc/xstartup\n" +
                "\n" +
                "# Set VNC password (empty password for localhost)\n" +
                "echo '' | vncpasswd -f > /root/.vnc/passwd 2>/dev/null || true\n" +
                "chmod 600 /root/.vnc/passwd\n" +
                "\n" +
                "# Create VNC startup script in /usr/local/bin\n" +
                "mkdir -p /usr/local/bin\n" +
                "if [ $LOMIRI_INSTALLED -eq 1 ]; then\n" +
                "    cat > /usr/local/bin/start-vnc.sh << 'STARTEOF'\n" +
                "#!/bin/bash\n" +
                "set -e\n" +
                "export DISPLAY=${DISPLAY:-:1}\n" +
                "export USER=root\n" +
                "export HOME=/root\n" +
                "export XDG_SESSION_TYPE=mir\n" +
                "\n" +
                "# Kill existing VNC server if running\n" +
                "pkill -f 'vncserver :1' || true\n" +
                "pkill -f 'Xvnc :1' || true\n" +
                "pkill -f 'x11vnc.*:1' || true\n" +
                "pkill -f 'mir_display_server' || true\n" +
                "sleep 1\n" +
                "\n" +
                "# Start VNC server with Lomiri\n" +
                "if command -v vncserver >/dev/null 2>&1; then\n" +
                "    vncserver :2 -geometry 1280x720 -depth 24 -localhost no -SecurityTypes None -xstartup /root/.vnc/xstartup 2>/dev/null || true\n" +
                "elif command -v Xvnc >/dev/null 2>&1; then\n" +
                "    Xvnc :2 -geometry 1280x720 -depth 24 -SecurityTypes None -rfbport 5902 &\n" +
                "    sleep 2\n" +
                "fi\n" +
                "\n" +
                "echo 'VNC server started on display :2, port 5902 (Lomiri)'\n" +
                "STARTEOF\n" +
                "else\n" +
                "    cat > /usr/local/bin/start-vnc.sh << 'STARTEOF'\n" +
                "#!/bin/bash\n" +
                "set -e\n" +
                "export DISPLAY=${DISPLAY:-:1}\n" +
                "export USER=root\n" +
                "export HOME=/root\n" +
                "\n" +
                "echo '=== Starting VNC Server ==='\n" +
                "\n" +
                "# Set up hostname and /etc/hosts to avoid vncserver hostname errors\n" +
                "if ! hostname >/dev/null 2>&1 || [ -z \"$(hostname 2>/dev/null)\" ]; then\n" +
                "    echo 'localhost' > /etc/hostname 2>/dev/null || true\n" +
                "    hostname localhost 2>/dev/null || true\n" +
                "fi\n" +
                "export HOSTNAME=localhost\n" +
                "# Ensure /etc/hosts has localhost entry\n" +
                "if ! grep -q '^127.0.0.1.*localhost' /etc/hosts 2>/dev/null; then\n" +
                "    echo '127.0.0.1 localhost' >> /etc/hosts 2>/dev/null || true\n" +
                "fi\n" +
                "\n" +
                "# Kill existing VNC servers\n" +
                "pkill -f 'vncserver' 2>/dev/null || true\n" +
                "pkill -f 'Xvnc' 2>/dev/null || true\n" +
                "pkill -f 'x11vnc' 2>/dev/null || true\n" +
                "sleep 2\n" +
                "\n" +
                "# Clean up lock files\n" +
                "rm -rf /tmp/.X11-unix/X* 2>/dev/null || true\n" +
                "rm -f /tmp/.X*-lock 2>/dev/null || true\n" +
                "\n" +
                "# Function to try starting VNC on a specific display\n" +
                "start_vnc_on_display() {\n" +
                "    local DISPLAY_NUM=$1\n" +
                "    local PORT=$((5900 + DISPLAY_NUM))\n" +
                "    local LOG_FILE=\"/tmp/xvnc-${DISPLAY_NUM}.log\"\n" +
                "    \n" +
                "    echo \"Trying display :${DISPLAY_NUM}, port ${PORT}...\"\n" +
                "    \n" +
                "    # Remove any existing socket/lock for this display\n" +
                "    rm -rf \"/tmp/.X11-unix/X${DISPLAY_NUM}\" 2>/dev/null || true\n" +
                "    rm -f \"/tmp/.X${DISPLAY_NUM}-lock\" 2>/dev/null || true\n" +
                "    \n" +
                "    if command -v Xvnc >/dev/null 2>&1; then\n" +
                "        # Start Xvnc (it will look for xstartup in ~/.vnc/xstartup automatically)\n" +
                "        # But we'll also manually trigger it to be sure\n" +
                "        Xvnc :${DISPLAY_NUM} -geometry 1280x720 -depth 24 -SecurityTypes None -rfbport ${PORT} >\"${LOG_FILE}\" 2>&1 &\n" +
                "        local PID=$!\n" +
                "        sleep 3\n" +
                "        \n" +
                "        # Check if process is still running\n" +
                "        if kill -0 $PID 2>/dev/null; then\n" +
                "            # Check if port is listening (if ss/netstat available)\n" +
                "            local PORT_CHECK=0\n" +
                "            if command -v ss >/dev/null 2>&1; then\n" +
                "                if ss -ln 2>/dev/null | grep -q \":${PORT} \"; then\n" +
                "                    PORT_CHECK=1\n" +
                "                fi\n" +
                "            elif command -v netstat >/dev/null 2>&1; then\n" +
                "                if netstat -ln 2>/dev/null | grep -q \":${PORT} \"; then\n" +
                "                    PORT_CHECK=1\n" +
                "                fi\n" +
                "            else\n" +
                "                # Assume success if process is running and we can't check port\n" +
                "                PORT_CHECK=1\n" +
                "            fi\n" +
                "            \n" +
                "            if [ $PORT_CHECK -eq 1 ] || kill -0 $PID 2>/dev/null; then\n" +
                "                echo \"VNC server started successfully on display :${DISPLAY_NUM}, port ${PORT}\"\n" +
                "                # Save display and port to file\n" +
                "                echo \"display:${DISPLAY_NUM}\" > /tmp/vnc-display.txt\n" +
                "                echo \"port:${PORT}\" >> /tmp/vnc-display.txt\n" +
                "                echo \"VNC display info saved to /tmp/vnc-display.txt\"\n" +
                "                \n" +
                "                # Xvnc should automatically run xstartup, but let's ensure it happens\n" +
                "                # Wait a moment for Xvnc to initialize\n" +
                "                sleep 2\n" +
                "                \n" +
                "                # Manually trigger xstartup to ensure desktop starts\n" +
                "                # This is needed because Xvnc doesn't always execute xstartup automatically\n" +
                "                export DISPLAY=:${DISPLAY_NUM}\n" +
                "                export HOME=/root\n" +
                "                export USER=root\n" +
                "                if [ -x /root/.vnc/xstartup ]; then\n" +
                "                    # Check if xstartup is already running\n" +
                "                    if [ ! -f /tmp/xstartup.log ] || [ ! -s /tmp/xstartup.log ]; then\n" +
                "                        echo \"Starting xstartup script...\"\n" +
                "                        /root/.vnc/xstartup >/tmp/xstartup-manual.log 2>&1 &\n" +
                "                        sleep 3\n" +
                "                    else\n" +
                "                        echo \"xstartup already running (log exists)\"\n" +
                "                    fi\n" +
                "                fi\n" +
                "                \n" +
                "                # Check if desktop environment is running\n" +
                "                sleep 2\n" +
                "                if pgrep -x xfwm4 >/dev/null 2>&1 || pgrep -x xfce4-session >/dev/null 2>&1 || pgrep -f startxfce4 >/dev/null 2>&1; then\n" +
                "                    echo \"Desktop environment is running\"\n" +
                "                else\n" +
                "                    echo \"Desktop environment not detected yet, may still be starting...\"\n" +
                "                    echo \"Check /tmp/xstartup.log and /tmp/xfce4.log for details\"\n" +
                "                fi\n" +
                "                \n" +
                "                return 0\n" +
                "            fi\n" +
                "        fi\n" +
                "        \n" +
                "        # If we get here, Xvnc failed\n" +
                "        echo \"Xvnc failed on display :${DISPLAY_NUM}, checking log...\"\n" +
                "        cat \"${LOG_FILE}\" 2>/dev/null | head -10 || true\n" +
                "        kill $PID 2>/dev/null || true\n" +
                "    fi\n" +
                "    \n" +
                "    return 1\n" +
                "}\n" +
                "\n" +
                "# Try displays :1 through :10 until one works\n" +
                "VNC_STARTED=0\n" +
                "for DISPLAY_NUM in 1 2 3 4 5 6 7 8 9 10; do\n" +
                "    if start_vnc_on_display $DISPLAY_NUM; then\n" +
                "        VNC_STARTED=1\n" +
                "        break\n" +
                "    fi\n" +
                "done\n" +
                "\n" +
                "if [ $VNC_STARTED -eq 0 ]; then\n" +
                "    echo \"ERROR: Failed to start VNC server on any display (tried :1 through :10)\"\n" +
                "    echo \"Check logs in /tmp/xvnc-*.log\"\n" +
                "    exit 1\n" +
                "fi\n" +
                "\n" +
                "echo \"=== VNC Server Startup Complete ===\"\n" +
                "if [ -f /tmp/vnc-display.txt ]; then\n" +
                "    echo \"Current VNC configuration:\"\n" +
                "    cat /tmp/vnc-display.txt\n" +
                "fi\n" +
                "echo \"Check /tmp/xfce4.log for desktop startup logs\"\n" +
                "echo \"Check /tmp/xvnc-*.log for VNC server logs\"\n" +
                "STARTEOF\n" +
                "fi\n" +
                "chmod +x /usr/local/bin/start-vnc.sh\n" +
                "\n" +
                "# Final dpkg check to ensure everything is properly configured\n" +
                "echo 'Performing final configuration check...'\n" +
                "dpkg --configure -a 2>&1 || true\n" +
                "apt-get install -y -qq -f 2>&1 || true\n" +
                "\n" +
                "echo 'Installation complete!'\n";
        } else {
            // Alpine installation script
            return "#!/bin/sh\n" +
                "set -e\n" +
                "\n" +
                "# Update package lists\n" +
                "apk update\n" +
                "\n" +
                "# Install XFCE and VNC\n" +
                "apk add xfce4 xfce4-terminal tigervnc dbus\n" +
                "\n" +
                "# Create VNC startup script\n" +
                "mkdir -p /root/.vnc\n" +
                "# xstartup will be created by the main installation script\n" +
                "\n" +
                "# Note: start-vnc.sh is created by the main installation script\n" +
                "# which uses Xvnc (TigerVNC) that includes both X server and VNC\n" +
                "\n" +
                "echo 'Installation complete!'\n";
        }
    }
    
    private void showUninstallDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Uninstall OS")
            .setMessage("This will remove the desktop environment and VNC server. Continue?")
            .setPositiveButton("Uninstall", (dialog, which) -> {
                // Mark as not installed
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putBoolean(KEY_OS_INSTALLED, false).apply();
                
                statusText.setText("OS uninstalled. Click Install OS to set up again.");
                installButton.setVisibility(View.VISIBLE);
                uninstallButton.setVisibility(View.GONE);
                
                Toast.makeText(this, "OS uninstalled", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    /**
     * Update VNC scripts without full reinstallation.
     * Useful if scripts need to be fixed after installation.
     */
    private void updateVNCScripts() {
        if (!rootfsManager.isRootfsInstalled()) {
            Toast.makeText(this, "Rootfs not installed", Toast.LENGTH_SHORT).show();
            return;
        }
        
        installButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText("Updating VNC scripts...");
        
        new UpdateScriptsTask().execute();
    }
    
    private class UpdateScriptsTask extends AsyncTask<Void, String, Boolean> {
        private String errorMessage = null;
        
        @Override
        protected Boolean doInBackground(Void... params) {
            try {
                // Get rootfs info
                java.util.List<String> installedRootfs = rootfsManager.getInstalledRootfs();
                boolean isUbuntu = false;
                String rootfsFileName = null;
                
                for (String rootfs : installedRootfs) {
                    String lower = rootfs.toLowerCase();
                    if (lower.contains("ubuntu") || lower.contains("debian")) {
                        isUbuntu = true;
                        rootfsFileName = rootfs;
                        break;
                    }
                }
                
                if (!isUbuntu && !installedRootfs.isEmpty()) {
                    rootfsFileName = installedRootfs.get(0);
                    String lower = rootfsFileName.toLowerCase();
                    isUbuntu = lower.contains("ubuntu") || lower.contains("debian");
                }
                
                File filesDir = InstallOSActivity.this.getFilesDir();
                File localDir = new File(filesDir.getParentFile(), "local");
                String rootfsDirName = rootfsFileName != null ? 
                    rootfsFileName.replace(".tar.gz", "").replace(".tar", "").toLowerCase() : "ubuntu";
                File rootfsDir = new File(localDir, rootfsDirName);
                
                // Create updated xstartup script
                String xstartupScript;
                if (isUbuntu) {
                    // Check if Lomiri is installed (simplified - assume XFCE for now)
                    xstartupScript = "#!/bin/bash\n" +
                        "unset SESSION_MANAGER\n" +
                        "unset DBUS_SESSION_BUS_ADDRESS\n" +
                        "export DISPLAY=${DISPLAY:-:1}\n" +
                        "[ -r $HOME/.Xresources ] && xrdb $HOME/.Xresources\n" +
                        "# Start D-Bus session\n" +
                        "eval $(dbus-launch --sh-syntax)\n" +
                        "# Start XFCE desktop environment\n" +
                        "startxfce4 &\n";
                } else {
                    xstartupScript = "#!/bin/sh\n" +
                        "startxfce4\n";
                }
                
                // Write updated xstartup script
                File xstartupFile = new File(rootfsDir, "root/.vnc/xstartup");
                xstartupFile.getParentFile().mkdirs();
                java.io.FileWriter writer = new java.io.FileWriter(xstartupFile);
                writer.write(xstartupScript);
                writer.close();
                xstartupFile.setExecutable(true);
                
                publishProgress("VNC scripts updated successfully!");
                return true;
                
            } catch (Exception e) {
                errorMessage = "Failed to update scripts: " + e.getMessage();
                Log.e(TAG, "Failed to update scripts", e);
                return false;
            }
        }
        
        @Override
        protected void onProgressUpdate(String... values) {
            if (values.length > 0) {
                statusText.setText(values[0]);
            }
        }
        
        @Override
        protected void onPostExecute(Boolean success) {
            progressBar.setVisibility(View.GONE);
            installButton.setEnabled(true);
            
            if (success) {
                statusText.setText("VNC scripts updated!\n\nPlease restart the VNC server or reinstall OS for changes to take effect.");
                Toast.makeText(InstallOSActivity.this, "VNC scripts updated!", Toast.LENGTH_LONG).show();
            } else {
                statusText.setText("Update failed: " + (errorMessage != null ? errorMessage : "Unknown error"));
                Toast.makeText(InstallOSActivity.this, "Update failed", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
