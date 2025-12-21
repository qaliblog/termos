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
            ExecutionCommand executionCommand = new ExecutionCommand(
                "linux-cmd-" + System.currentTimeMillis(),
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
                    if (appShell == null || appShell.mExecutionCommand == null) {
                        if (callback != null) {
                            callback.onError("Command execution failed");
                        }
                        return;
                    }
                    
                    ExecutionCommand cmd = appShell.getExecutionCommand();
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
     * Uses netstat or ss command if available.
     */
    public void checkVNCServerRunning(com.termux.terminal.TerminalSessionClient serviceClient,
                                      ServerCheckCallback callback) {
        // Try to check if port 5901 is listening
        executeCommand("netstat -ln 2>/dev/null | grep ':5901 ' || ss -ln 2>/dev/null | grep ':5901 ' || echo 'not_found'", 
            serviceClient,
            new CommandCallback() {
                @Override
                public void onSuccess(String output) {
                    boolean isRunning = output != null && output.contains("5901") && !output.contains("not_found");
                    callback.onResult(isRunning);
                }
                
                @Override
                public void onError(String error) {
                    // If check command fails, assume server is not running
                    callback.onResult(false);
                }
            });
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
                    executeCommand(vncCommand, serviceClient, callback);
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
