package com.termos.app.vnc;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.iiordanov.bVNC.ConnectionBean;
import com.iiordanov.bVNC.Constants;
import com.iiordanov.bVNC.RemoteCanvas;
import com.iiordanov.bVNC.input.RemoteCanvasHandler;
import com.iiordanov.bVNC.protocol.RemoteVncConnection;
import com.termos.app.linuxruntime.LinuxCommandExecutor;
import com.termux.terminal.TerminalSessionClient;
import com.undatech.opaque.RemoteClientLibConstants;
import com.iiordanov.bVNC.protocol.RemoteConnection;

/**
 * Manages VNC connection lifecycle for the OS tab.
 * Connects to localhost VNC server running in the Linux rootfs.
 */
public class VNCConnectionManager {
    private static final String TAG = "VNCConnectionManager";
    private static final String VNC_HOST = "127.0.0.1";
    private static final int VNC_PORT = 5901; // Display :1
    
    private static VNCConnectionManager instance;
    private Context context;
    private RemoteCanvas canvas;
    private ConnectionBean connection;
    private RemoteConnection remoteConnection;
    private RemoteCanvasHandler handler;
    private LinuxCommandExecutor commandExecutor;
    private TerminalSessionClient serviceClient;
    private boolean isConnected = false;
    private boolean isPaused = false;
    private int connectionRetryCount = 0;
    private static final int MAX_CONNECTION_RETRIES = 10; // Maximum retries before giving up
    private static final int CONNECTION_TIMEOUT_MS = 30000; // 30 seconds timeout
    private Handler timeoutHandler;
    
    private VNCConnectionManager(Context context) {
        this.context = context.getApplicationContext();
        this.commandExecutor = new LinuxCommandExecutor(context);
    }
    
    /**
     * Set the terminal session client for executing commands.
     * Call this before connecting.
     */
    public void setServiceClient(TerminalSessionClient serviceClient) {
        this.serviceClient = serviceClient;
    }
    
    public static synchronized VNCConnectionManager getInstance(Context context) {
        if (instance == null) {
            instance = new VNCConnectionManager(context);
        }
        return instance;
    }
    
    /**
     * Initialize VNC connection with the RemoteCanvas widget.
     * Call this from OstabFragment.onViewCreated()
     * @param canvas The RemoteCanvas widget to display VNC content
     * @param activityContext Activity context required for showing dialogs (must be an Activity, not Application context)
     */
    public void initialize(RemoteCanvas canvas, Activity activityContext) {
        this.canvas = canvas;
        
        // Create connection bean for localhost VNC
        // Use activity context for ConnectionBean as it may need Activity context
        connection = new ConnectionBean(activityContext);
        connection.setAddress(VNC_HOST);
        connection.setPort(VNC_PORT);
        connection.setNickname("Termos OS");
        connection.setConnectionType(Constants.CONN_TYPE_PLAIN);
        connection.setColorModel(com.iiordanov.bVNC.COLORMODEL.C24bit.nameString());
        connection.setPrefEncoding(7); // RfbProto.EncodingTight
        connection.setPassword(""); // No password by default, can be configured later
        connection.setKeepPassword(false);
        
        // Set up canvas
        canvas.setFocusableInTouchMode(true);
        canvas.setDrawingCacheEnabled(false);
        
        // Create remote connection directly (VNC connection)
        // We use RemoteVncConnection directly instead of RemoteConnectionFactory
        // because the factory checks package name which doesn't contain "vnc"
        // Must use Activity context here as RemoteConnection constructor shows a ProgressDialog
        Runnable hideKeyboardAndExtraKeys = () -> {
            // No-op for now, can be implemented if needed
        };
        
        remoteConnection = new RemoteVncConnection(
            activityContext,
            connection,
            canvas,
            hideKeyboardAndExtraKeys
        );
        
        // Create handler
        Runnable setModes = () -> {
            // Set connection modes if needed
        };
        
        handler = new RemoteCanvasHandler(
            activityContext,
            canvas,
            remoteConnection,
            connection,
            setModes
        );
        
        Log.d(TAG, "VNC connection manager initialized");
        
        // Note: Connection is not started here. It will be started when connect() is called.
        // The ProgressDialog shown by RemoteConnection constructor will remain visible
        // until the connection actually starts or fails.
    }
    
    /**
     * Connect to VNC server.
     * Auto-starts VNC server if not running.
     * Call this when OS tab becomes visible.
     */
    public void connect() {
        if (canvas == null || connection == null || remoteConnection == null) {
            Log.e(TAG, "Cannot connect: not initialized");
            return;
        }
        
        if (isConnected && !isPaused) {
            Log.d(TAG, "Already connected");
            return;
        }
        
        if (isPaused) {
            // Resume connection
            resume();
            return;
        }
        
        // Reset retry count when starting a new connection attempt
        connectionRetryCount = 0;
        
        Log.d(TAG, "Connecting to VNC server at " + VNC_HOST + ":" + VNC_PORT);
        
        // Auto-start VNC server if not running
        if (serviceClient != null) {
            commandExecutor.startVNCServerIfNeeded(serviceClient, new LinuxCommandExecutor.CommandCallback() {
                @Override
                public void onSuccess(String output) {
                    Log.d(TAG, "VNC server ready: " + output);
                    // Verify server is actually listening before connecting
                    verifyAndConnect();
                }
                
                @Override
                public void onError(String error) {
                    Log.e(TAG, "Failed to start VNC server: " + error);
                    // Try to connect anyway - server might already be running
                    doConnect();
                }
            });
        } else {
            // No service client - try to connect directly
            Log.w(TAG, "No service client available, attempting direct connection");
            doConnect();
        }
    }
    
    /**
     * Verify VNC server is listening and then connect.
     */
    private void verifyAndConnect() {
        if (serviceClient == null) {
            Log.w(TAG, "No service client, attempting direct connection");
            doConnect();
            return;
        }
        
        // Check retry limit
        if (connectionRetryCount >= MAX_CONNECTION_RETRIES) {
            Log.e(TAG, "Max connection retries reached, giving up");
            // Dismiss progress dialog if connection failed
            dismissProgressDialog();
            return;
        }
        
        connectionRetryCount++;
        
        // Check if VNC server is actually listening on the port
        commandExecutor.checkVNCServerRunning(serviceClient, new LinuxCommandExecutor.ServerCheckCallback() {
            @Override
            public void onResult(boolean isRunning) {
                if (isRunning) {
                    Log.d(TAG, "VNC server confirmed listening, connecting...");
                    connectionRetryCount = 0; // Reset retry count on success
                    doConnect();
                } else {
                    Log.w(TAG, "VNC server not listening yet (attempt " + connectionRetryCount + "/" + MAX_CONNECTION_RETRIES + "), waiting and retrying...");
                    // Retry after a delay
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        verifyAndConnect();
                    }, 1000);
                }
            }
        });
    }
    
    /**
     * Perform the actual VNC connection.
     */
    private void doConnect() {
        // Check if connection is ready
        if (!connection.isReadyForConnection()) {
            Log.e(TAG, "Connection not ready: missing required parameters");
            return;
        }
        
        // Check if handler is available
        if (handler == null) {
            Log.e(TAG, "Handler not available, cannot connect");
            return;
        }
        
        Log.d(TAG, "Starting VNC connection...");
        
        // Set up timeout handler to dismiss dialog if connection takes too long
        timeoutHandler = new Handler(Looper.getMainLooper());
        timeoutHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Check if dialog is still showing (connection hasn't completed)
                if (remoteConnection != null && remoteConnection.pd != null && remoteConnection.pd.isShowing()) {
                    Log.w(TAG, "Connection timeout - dismissing progress dialog");
                    try {
                        remoteConnection.pd.dismiss();
                    } catch (Exception e) {
                        Log.e(TAG, "Error dismissing progress dialog on timeout", e);
                    }
                }
            }
        }, CONNECTION_TIMEOUT_MS);
        
        // Start connection by sending REINIT_SESSION message
        // This will call initializeConnection() which starts the connection thread
        handler.sendEmptyMessage(RemoteClientLibConstants.REINIT_SESSION);
        
        // Note: isConnected will be set to true when connection actually succeeds
        // We set it here optimistically, but the connection might fail
        // The actual connection state should be tracked by RemoteConnection
    }
    
    /**
     * Pause VNC connection (keep connection alive but stop rendering).
     * Call this when OS tab becomes hidden.
     */
    public void pause() {
        if (!isConnected) {
            return;
        }
        
        Log.d(TAG, "Pausing VNC connection");
        isPaused = true;
        // Note: bVNC doesn't have explicit pause, but we can stop rendering
        // The connection will remain alive
    }
    
    /**
     * Resume VNC connection.
     * Call this when OS tab becomes visible again.
     */
    public void resume() {
        if (!isConnected) {
            connect();
            return;
        }
        
        if (!isPaused) {
            return;
        }
        
        Log.d(TAG, "Resuming VNC connection");
        isPaused = false;
        // Connection should automatically resume rendering
    }
    
    /**
     * Dismiss the progress dialog if it's showing.
     */
    private void dismissProgressDialog() {
        if (remoteConnection != null && remoteConnection.pd != null && remoteConnection.pd.isShowing()) {
            try {
                remoteConnection.pd.dismiss();
            } catch (Exception e) {
                Log.e(TAG, "Error dismissing progress dialog", e);
            }
        }
    }
    
    /**
     * Disconnect from VNC server.
     * Call this when OS tab is destroyed.
     */
    public void disconnect() {
        // Cancel timeout handler
        if (timeoutHandler != null) {
            timeoutHandler.removeCallbacksAndMessages(null);
            timeoutHandler = null;
        }
        
        if (!isConnected) {
            // Still dismiss dialog if showing
            dismissProgressDialog();
            return;
        }
        
        Log.d(TAG, "Disconnecting from VNC server");
        
        if (remoteConnection != null) {
            try {
                remoteConnection.closeConnection();
            } catch (Exception e) {
                Log.e(TAG, "Error disconnecting", e);
            }
        }
        
        isConnected = false;
        isPaused = false;
    }
    
    /**
     * Check if VNC is currently connected.
     */
    public boolean isConnected() {
        return isConnected && !isPaused;
    }
    
    /**
     * Get the RemoteCanvas widget.
     */
    public RemoteCanvas getCanvas() {
        return canvas;
    }
    
    /**
     * Get the connection handler.
     */
    public RemoteCanvasHandler getHandler() {
        return handler;
    }
}

