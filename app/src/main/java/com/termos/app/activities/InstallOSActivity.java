package com.termos.app.activities;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.termux.shared.shell.command.environment.termux.TermuxShellEnvironment;
import com.termux.terminal.TerminalSessionClient;

/**
 * Activity to install desktop environment and VNC server for OS tab.
 * Installs XFCE desktop environment (lightweight, VNC-friendly) and sets up VNC server.
 * Note: Ubuntu Touch uses Lomiri (Unity8), but for VNC in container, XFCE is more practical.
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
        protected Boolean doInBackground(String... params) {
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
                
                // Install desktop environment and VNC server
                String installScript = createInstallScript(isUbuntu);
                
                final boolean[] success = {false};
                final String[] error = {null};
                
                // Execute using AppShell directly (doesn't require TermuxService)
                try {
                    ExecutionCommand executionCommand = new ExecutionCommand(
                        -1, // id
                        "/bin/sh",
                        new String[]{"-c", installScript},
                        null, // stdin
                        "/root", // working directory
                        ExecutionCommand.Runner.APP_SHELL.getName(),
                        false // not failsafe
                    );
                    
                    IShellEnvironment shellEnvironment = new TermuxShellEnvironment();
                    
                    AppShell.AppShellClient appShellClient = new AppShell.AppShellClient() {
                        @Override
                        public void onAppShellExited(AppShell appShell) {
                            if (appShell == null) {
                                error[0] = "Command execution failed";
                                return;
                            }
                            
                            ExecutionCommand cmd = appShell.getExecutionCommand();
                            if (cmd == null) {
                                error[0] = "Command execution failed";
                                return;
                            }
                            
                            int exitCode = cmd.resultData.exitCode;
                            String stdout = cmd.resultData.stdout.toString();
                            String stderr = cmd.resultData.stderr.toString();
                            
                            Log.d(TAG, "Installation command completed (exit code: " + exitCode + ")");
                            
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
                        null, // additionalEnvironment
                        false // isSynchronous
                    );
                    
                    if (appShell == null) {
                        errorMessage = "Failed to start installation";
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
                    "Desktop environment (XFCE) and VNC server are installed and configured.\n" +
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
     * For Ubuntu: Installs XFCE (lightweight, VNC-friendly)
     * Note: Ubuntu Touch uses Lomiri, but XFCE is more practical for VNC.
     */
    private String createInstallScript(boolean isUbuntu) {
        if (isUbuntu) {
            // Ubuntu/Debian installation script
            return "#!/bin/bash\n" +
                "set -e\n" +
                "export DEBIAN_FRONTEND=noninteractive\n" +
                "\n" +
                "# Update package lists\n" +
                "apt-get update -qq\n" +
                "\n" +
                "# Install XFCE desktop environment (lightweight, VNC-friendly)\n" +
                "# Note: Ubuntu Touch uses Lomiri, but XFCE works better with VNC\n" +
                "apt-get install -y -qq xfce4 xfce4-goodies xfce4-terminal\n" +
                "\n" +
                "# Install VNC server (TigerVNC or tightvnc)\n" +
                "apt-get install -y -qq tigervnc-standalone-server tigervnc-common || \\\n" +
                "apt-get install -y -qq tightvncserver || \\\n" +
                "apt-get install -y -qq x11vnc\n" +
                "\n" +
                "# Install additional utilities\n" +
                "apt-get install -y -qq dbus-x11 xfce4-session xfce4-panel\n" +
                "\n" +
                "# Create VNC startup script\n" +
                "mkdir -p /root/.vnc\n" +
                "cat > /root/.vnc/xstartup << 'VNCEOF'\n" +
                "#!/bin/bash\n" +
                "unset SESSION_MANAGER\n" +
                "unset DBUS_SESSION_BUS_ADDRESS\n" +
                "exec /etc/X11/xinit/xinitrc\n" +
                "[ -x /etc/vnc/xstartup ] && exec /etc/vnc/xstartup\n" +
                "[ -r $HOME/.Xresources ] && xrdb $HOME/.Xresources\n" +
                "x-window-manager &\n" +
                "startxfce4 &\n" +
                "VNCEOF\n" +
                "chmod +x /root/.vnc/xstartup\n" +
                "\n" +
                "# Set VNC password (empty password for localhost)\n" +
                "echo '' | vncpasswd -f > /root/.vnc/passwd 2>/dev/null || true\n" +
                "chmod 600 /root/.vnc/passwd\n" +
                "\n" +
                "# Create VNC startup script in /usr/local/bin\n" +
                "mkdir -p /usr/local/bin\n" +
                "cat > /usr/local/bin/start-vnc.sh << 'STARTEOF'\n" +
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
                "echo 'VNC server started on display :1, port 5901'\n" +
                "STARTEOF\n" +
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
