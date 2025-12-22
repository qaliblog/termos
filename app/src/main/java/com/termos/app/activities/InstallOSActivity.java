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
                publishProgress("Detected " + distroName + " distribution\nInstalling desktop environment...");
                
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
                    
                    // Verify proot exists (required by init-host script)
                    // init-host expects proot at $PREFIX/local/bin/proot
                    File prootFile = new File(localBinDir, "proot");
                    if (!prootFile.exists()) {
                        // Check if proot is in files directory (where RootfsDownloader puts it)
                        File prootInFiles = new File(filesDir, "proot");
                        if (prootInFiles.exists()) {
                            // Copy proot to local/bin
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
                    } else {
                        Log.d(TAG, "Proot found at: " + prootFile.getAbsolutePath());
                    }
                    
                    // Command to execute within rootfs
                    // The init-host script expects arguments that will be passed to the shell inside rootfs
                    // We'll call it with -c and our script path
                    String shellCommand;
                    String[] shellArgs;
                    
                    if (initScriptFile.exists() && initScriptFile.canExecute()) {
                        // Use init-host script which will set up proot and execute the command
                        // The init-host script will pass arguments to /bin/init inside rootfs
                        shellCommand = initScriptFile.getAbsolutePath();
                        shellArgs = new String[]{"-c", "/root/install-os.sh"};
                        Log.d(TAG, "Using init-host script: " + shellCommand);
                    } else {
                        // Fallback: use /bin/sh directly (won't work in rootfs, but might help debug)
                        Log.w(TAG, "Init script not found or not executable, using /bin/sh fallback");
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
                    java.util.HashMap<String, String> additionalEnvironment = new java.util.HashMap<>();
                    additionalEnvironment.put("ROOTFS_FILE", rootfsFileName);
                    additionalEnvironment.put("ROOTFS_DIR", rootfsDirName);
                    // Add PREFIX for init-host script
                    additionalEnvironment.put("PREFIX", filesDir.getParentFile().getAbsolutePath());
                    
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
                    
                    publishProgress("Running installation script...");
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
                    while (!success[0] && error[0] == null && 
                           (System.currentTimeMillis() - startTime) < 600000) { // 10 minute timeout
                        try {
                            Thread.sleep(2000);
                            publishProgress("Installing... (this may take several minutes)");
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    
                    if (error[0] != null) {
                        errorMessage = error[0];
                        return false;
                    }
                    
                    if (!success[0]) {
                        errorMessage = "Installation timed out";
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
                "# Update package lists\n" +
                "apt-get update -qq\n" +
                "\n" +
                "# Try to install Lomiri (Ubuntu Touch desktop environment)\n" +
                "# Lomiri packages may not be in standard repos, so we'll try and fallback to XFCE\n" +
                "LOMIRI_INSTALLED=0\n" +
                "if apt-get install -y -qq lomiri-session unity8-desktop-session-mir 2>/dev/null; then\n" +
                "    LOMIRI_INSTALLED=1\n" +
                "    echo 'Lomiri installed successfully'\n" +
                "else\n" +
                "    echo 'Lomiri packages not available, installing XFCE as fallback...'\n" +
                "    apt-get install -y -qq xfce4 xfce4-goodies xfce4-terminal || true\n" +
                "fi\n" +
                "\n" +
                "# Install Mir display server (required for Lomiri)\n" +
                "if [ $LOMIRI_INSTALLED -eq 1 ]; then\n" +
                "    apt-get install -y -qq mir mir-graphics-drivers-nvidia mir-graphics-drivers-mesa || true\n" +
                "fi\n" +
                "\n" +
                "# Install VNC server (TigerVNC or tightvnc)\n" +
                "apt-get install -y -qq tigervnc-standalone-server tigervnc-common || \\\n" +
                "apt-get install -y -qq tightvncserver || \\\n" +
                "apt-get install -y -qq x11vnc\n" +
                "\n" +
                "# Install additional utilities\n" +
                "apt-get install -y -qq dbus-x11\n" +
                "if [ $LOMIRI_INSTALLED -eq 0 ]; then\n" +
                "    apt-get install -y -qq xfce4-session xfce4-panel\n" +
                "fi\n" +
                "\n" +
                "# Create VNC startup script\n" +
                "mkdir -p /root/.vnc\n" +
                "if [ $LOMIRI_INSTALLED -eq 1 ]; then\n" +
                "    # Lomiri startup script\n" +
                "    cat > /root/.vnc/xstartup << 'VNCEOF'\n" +
                "#!/bin/bash\n" +
                "unset SESSION_MANAGER\n" +
                "unset DBUS_SESSION_BUS_ADDRESS\n" +
                "export DISPLAY=:1\n" +
                "export XDG_SESSION_TYPE=mir\n" +
                "export MIR_SOCKET=/run/mir_socket\n" +
                "[ -x /etc/vnc/xstartup ] && exec /etc/vnc/xstartup\n" +
                "[ -r $HOME/.Xresources ] && xrdb $HOME/.Xresources\n" +
                "# Start Mir display server\n" +
                "mir_display_server --wayland-socket-name=wayland-0 &\n" +
                "sleep 2\n" +
                "# Start Lomiri session\n" +
                "lomiri-session &\n" +
                "VNCEOF\n" +
                "else\n" +
                "    # XFCE startup script (fallback)\n" +
                "    cat > /root/.vnc/xstartup << 'VNCEOF'\n" +
                "#!/bin/bash\n" +
                "unset SESSION_MANAGER\n" +
                "unset DBUS_SESSION_BUS_ADDRESS\n" +
                "exec /etc/X11/xinit/xinitrc\n" +
                "[ -x /etc/vnc/xstartup ] && exec /etc/vnc/xstartup\n" +
                "[ -r $HOME/.Xresources ] && xrdb $HOME/.Xresources\n" +
                "x-window-manager &\n" +
                "startxfce4 &\n" +
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
                "export DISPLAY=:1\n" +
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
                "    vncserver :1 -geometry 1280x720 -depth 24 -localhost no -SecurityTypes None -xstartup /root/.vnc/xstartup 2>/dev/null || true\n" +
                "elif command -v Xvnc >/dev/null 2>&1; then\n" +
                "    Xvnc :1 -geometry 1280x720 -depth 24 -SecurityTypes None -rfbport 5901 -xstartup /root/.vnc/xstartup &\n" +
                "    sleep 2\n" +
                "fi\n" +
                "\n" +
                "echo 'VNC server started on display :1, port 5901 (Lomiri)'\n" +
                "STARTEOF\n" +
                "else\n" +
                "    cat > /usr/local/bin/start-vnc.sh << 'STARTEOF'\n" +
                "#!/bin/bash\n" +
                "set -e\n" +
                "export DISPLAY=:1\n" +
                "export USER=root\n" +
                "export HOME=/root\n" +
                "\n" +
                "# Kill existing VNC server if running\n" +
                "pkill -f 'vncserver :1' || true\n" +
                "pkill -f 'Xvnc :1' || true\n" +
                "pkill -f 'x11vnc.*:1' || true\n" +
                "sleep 1\n" +
                "\n" +
                "# Start VNC server\n" +
                "if command -v vncserver >/dev/null 2>&1; then\n" +
                "    vncserver :1 -geometry 1280x720 -depth 24 -localhost no -SecurityTypes None -xstartup /root/.vnc/xstartup 2>/dev/null || true\n" +
                "elif command -v Xvnc >/dev/null 2>&1; then\n" +
                "    Xvnc :1 -geometry 1280x720 -depth 24 -SecurityTypes None -rfbport 5901 -xstartup /root/.vnc/xstartup &\n" +
                "    sleep 2\n" +
                "elif command -v x11vnc >/dev/null 2>&1; then\n" +
                "    # For x11vnc, we need X server first\n" +
                "    Xvfb :1 -screen 0 1280x720x24 &\n" +
                "    sleep 2\n" +
                "    DISPLAY=:1 startxfce4 &\n" +
                "    x11vnc -display :1 -rfbport 5901 -nopw -forever -shared &\n" +
                "fi\n" +
                "\n" +
                "echo 'VNC server started on display :1, port 5901 (XFCE)'\n" +
                "STARTEOF\n" +
                "fi\n" +
                "chmod +x /usr/local/bin/start-vnc.sh\n" +
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
                "apk add xfce4 xfce4-terminal x11vnc dbus\n" +
                "\n" +
                "# Create VNC startup script\n" +
                "mkdir -p /root/.vnc\n" +
                "cat > /root/.vnc/xstartup << 'VNCEOF'\n" +
                "#!/bin/sh\n" +
                "startxfce4\n" +
                "VNCEOF\n" +
                "chmod +x /root/.vnc/xstartup\n" +
                "\n" +
                "# Create VNC startup script\n" +
                "mkdir -p /usr/local/bin\n" +
                "cat > /usr/local/bin/start-vnc.sh << 'STARTEOF'\n" +
                "#!/bin/sh\n" +
                "export DISPLAY=:1\n" +
                "Xvfb :1 -screen 0 1280x720x24 &\n" +
                "sleep 2\n" +
                "DISPLAY=:1 startxfce4 &\n" +
                "x11vnc -display :1 -rfbport 5901 -nopw -forever -shared &\n" +
                "STARTEOF\n" +
                "chmod +x /usr/local/bin/start-vnc.sh\n" +
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
}
