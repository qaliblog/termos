package com.termos.app.linuxruntime;

import android.content.Context;
import android.util.Log;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.shell.command.environment.IShellEnvironment;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.util.HashMap;

/**
 * Executes commands in the Linux rootfs environment using proot.
 * Used for running setup scripts and starting VNC server.
 * 
 * Note: Commands are executed via AppShell which uses the Linux runtime
 * when rootfs is installed.
 */
public class LinuxCommandExecutor {
    private static final String TAG = "LinuxCommandExecutor";
    
    private Context context;
    private RootfsManager rootfsManager;
    private LinuxRuntimeManager runtimeManager;
    
    public LinuxCommandExecutor(Context context) {
        this.context = context.getApplicationContext();
        this.rootfsManager = new RootfsManager(context);
        this.runtimeManager = LinuxRuntimeManager.getInstance(context);
    }
    
    /**
     * Execute a command in the Linux rootfs environment.
     * Creates a background session to run the command.
     * 
     * @param command Command to execute (e.g., "/usr/local/bin/start-vnc.sh")
     * @param serviceClient Terminal session client (from TermuxService)
     * @param callback Optional callback for completion (can be null)
     * @return true if command execution was initiated
     */
    public boolean executeCommand(String command, 
                                  com.termux.terminal.TerminalSessionClient serviceClient,
                                  CommandCallback callback) {
        if (!rootfsManager.isRootfsInstalled()) {
            Log.e(TAG, "Cannot execute command: rootfs not installed");
            if (callback != null) {
                callback.onError("Rootfs not installed");
            }
            return false;
        }
        
        // Ensure scripts are ready
        runtimeManager.ensureSetupScriptReady();
        
        try {
            // Create execution command for background execution
            // Use -1 as id since this command is not managed by shell manager
            ExecutionCommand executionCommand = new ExecutionCommand(
                -1, // id
                "/bin/sh",
                new String[]{"-c", command},
                null, // stdin
                "/root", // working directory
                ExecutionCommand.Runner.APP_SHELL.getName(),
                false // not failsafe
            );
            
            // Create shell environment
            IShellEnvironment shellEnvironment = new TermuxShellEnvironment();
            
            // Create AppShellClient to handle completion
            AppShell.AppShellClient appShellClient = new AppShell.AppShellClient() {
                @Override
                public void onAppShellExited(AppShell appShell) {
                    if (appShell == null) {
                        if (callback != null) {
                            callback.onError("Command execution failed");
                        }
                        return;
                    }
                    
                    ExecutionCommand cmd = appShell.getExecutionCommand();
                    if (cmd == null) {
                        if (callback != null) {
                            callback.onError("Command execution failed");
                        }
                        return;
                    }
                    int exitCode = cmd.resultData.exitCode;
                    String stdout = cmd.resultData.stdout.toString();
                    String stderr = cmd.resultData.stderr.toString();
                    
                    Log.d(TAG, "Command execution completed: " + command + " (exit code: " + exitCode + ")");
                    
                    if (callback != null) {
                        if (exitCode == 0) {
                            callback.onSuccess(stdout);
                        } else {
                            callback.onError("Command failed with exit code " + exitCode + ": " + stderr);
                        }
                    }
                }
            };
            
            // Execute in background (isSynchronous = false)
            AppShell appShell = AppShell.execute(
                context,
                executionCommand,
                appShellClient,
                shellEnvironment,
                null, // additionalEnvironment
                false // isSynchronous
            );
            
            return appShell != null;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute command: " + command, e);
            if (callback != null) {
                callback.onError("Execution failed: " + e.getMessage());
            }
            return false;
        }
    }
    
    /**
     * Check if a process/port is listening (for VNC server check).
     * Uses netstat or ss command if available, or checks for VNC processes.
     */
    public void checkVNCServerRunning(com.termux.terminal.TerminalSessionClient serviceClient,
                                      ServerCheckCallback callback) {
        // Try multiple methods to check if VNC is running
        // Check port, processes, or if VNC log files exist
        // First try to read port from saved file, then check if it's listening
        executeCommand("PORT=$(cat /tmp/vnc-display.txt 2>/dev/null | grep '^port:' | cut -d: -f2); " +
            "if [ -n \"$PORT\" ]; then " +
            "  (ss -ln 2>/dev/null | grep \":$PORT \") || " +
            "  (netstat -ln 2>/dev/null | grep \":$PORT \") || " +
            "  (pgrep -f '[X]vnc.*:$PORT' >/dev/null 2>&1 && echo 'xvnc_process') || " +
            "  echo 'not_found'; " +
            "else " +
            "  (ss -ln 2>/dev/null | grep ':590[0-9] ') || " +
            "  (netstat -ln 2>/dev/null | grep ':590[0-9] ') || " +
            "  (test -f /tmp/vncserver.log && echo 'vncserver_log_exists') || " +
            "  (test -f /tmp/xvnc*.log && echo 'xvnc_log_exists') || " +
            "  (test -f /tmp/x11vnc.log && echo 'x11vnc_log_exists') || " +
            "  (pgrep -f '[X]vnc' >/dev/null 2>&1 && echo 'xvnc_process') || " +
            "  (pgrep -f '[v]ncserver' >/dev/null 2>&1 && echo 'vncserver_process') || " +
            "  (pgrep -f '[x]11vnc' >/dev/null 2>&1 && echo 'x11vnc_process') || " +
            "  echo 'not_found'; " +
            "fi", 
            serviceClient,
            new CommandCallback() {
                @Override
                public void onSuccess(String output) {
                    boolean isRunning = output != null && 
                        (output.contains("590") || 
                         output.contains("vncserver") || 
                         output.contains("xvnc") || 
                         output.contains("x11vnc") ||
                         output.contains("_log_exists") ||
                         output.contains("_process")) && 
                        !output.contains("not_found");
                    Log.d(TAG, "VNC server check result: " + output + " (isRunning: " + isRunning + ")");
                    callback.onResult(isRunning);
                }
                
                @Override
                public void onError(String error) {
                    Log.w(TAG, "VNC server check failed: " + error);
                    // If check command fails, assume server is not running
                    callback.onResult(false);
                }
            });
    }
    
    /**
     * Get information about running VNC servers.
     */
    public void getVNCServerStatus(com.termux.terminal.TerminalSessionClient serviceClient,
                                   CommandCallback callback) {
        executeCommand("if [ -f /usr/local/bin/vnc-status.sh ]; then /usr/local/bin/vnc-status.sh; else echo 'VNC status script not found. Run setup first.'; fi",
                      serviceClient, callback);
    }

    /**
     * Start VNC server if not already running.
     */
    public void startVNCServerIfNeeded(com.termux.terminal.TerminalSessionClient serviceClient,
                                       CommandCallback callback) {
        checkVNCServerRunning(serviceClient, new ServerCheckCallback() {
            @Override
            public void onResult(boolean isRunning) {
                if (isRunning) {
                    Log.d(TAG, "VNC server already running");
                    if (callback != null) {
                        callback.onSuccess("VNC server already running");
                    }
                } else {
                    Log.d(TAG, "Starting VNC server...");
                    String vncCommand = runtimeManager.getVNCStartCommand();
                    Log.d(TAG, "Executing VNC command: " + vncCommand);
                    
                    // Execute VNC startup command
                    // Use bash explicitly and ensure script is executable
                    // Make sure the script is executable first
                    String chmodCommand = "chmod +x " + vncCommand + " 2>/dev/null || true";
                    String fullCommand = chmodCommand + " && " + "bash " + vncCommand + " 2>&1";
                    Log.d(TAG, "Full VNC command: " + fullCommand);
                    
                    executeCommand(fullCommand, serviceClient, new CommandCallback() {
                        @Override
                        public void onSuccess(String output) {
                            Log.d(TAG, "VNC server start output: " + output);
                            // Wait a bit for server to fully start
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                if (callback != null) {
                                    callback.onSuccess(output);
                                }
                            }, 3000);
                        }
                        
                        @Override
                        public void onError(String error) {
                            Log.e(TAG, "VNC server start error: " + error);
                            // Check if server started despite error (might have started in background)
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                checkVNCServerRunning(serviceClient, new ServerCheckCallback() {
                                    @Override
                                    public void onResult(boolean isRunning) {
                                        if (isRunning) {
                                            Log.d(TAG, "VNC server is running despite error message");
                                            if (callback != null) {
                                                callback.onSuccess("VNC server started");
                                            }
                                        } else {
                                            Log.e(TAG, "VNC server not running: " + error);
                                            if (callback != null) {
                                                callback.onError(error);
                                            }
                                        }
                                    }
                                });
                            }, 5000);
                        }
                    });
                }
            }
        });
    }
    
    /**
     * Run setup script if not already completed.
     */
    public void runSetupIfNeeded(com.termux.terminal.TerminalSessionClient serviceClient,
                                 CommandCallback callback) {
        if (runtimeManager.isSetupComplete()) {
            Log.d(TAG, "Setup already completed");
            if (callback != null) {
                callback.onSuccess("Setup already completed");
            }
            return;
        }
        
        Log.d(TAG, "Running setup script...");
        String setupCommand = runtimeManager.getSetupCommand(false);
        executeCommand(setupCommand, serviceClient, callback);
    }
    
    /**
     * Callback interface for command execution.
     */
    public interface CommandCallback {
        void onSuccess(String output);
        void onError(String error);
    }
    
    /**
     * Callback interface for server status check.
     */
    public interface ServerCheckCallback {
        void onResult(boolean isRunning);
    }
}
