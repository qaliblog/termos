package com.termos.app.vnc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.iiordanov.bVNC.ConnectionBean;
import com.iiordanov.bVNC.Constants;
import com.iiordanov.bVNC.RemoteCanvas;
import com.iiordanov.bVNC.input.RemoteCanvasHandler;
import com.iiordanov.bVNC.protocol.RemoteConnectionFactory;
import com.iiordanov.bVNC.protocol.RemoteVncConnection;
import com.termos.app.linuxruntime.LinuxCommandExecutor;
import com.termux.terminal.TerminalSessionClient;
import com.undatech.opaque.RemoteClientLibConstants;
import com.undatech.opaque.RemoteConnection;

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
     */
    public void initialize(RemoteCanvas canvas) {
        this.canvas = canvas;
        
        // Create connection bean for localhost VNC
        connection = new ConnectionBean(context);
        connection.setAddress(VNC_HOST);
        connection.setPort(VNC_PORT);
        connection.setNickname("Termos OS");
        connection.setConnectionType(Constants.CONN_TYPE_PLAIN);
        connection.setColorModel(com.iiordanov.bVNC.COLORMODEL.C24bit.nameString());
        connection.setPrefEncoding(com.iiordanov.bVNC.RfbProto.EncodingTight);
        connection.setPassword(""); // No password by default, can be configured later
        connection.setKeepPassword(false);
        
        // Set up canvas
        canvas.setFocusableInTouchMode(true);
        canvas.setDrawingCacheEnabled(false);
        
        // Create remote connection factory
        Runnable hideKeyboardAndExtraKeys = () -> {
            // No-op for now, can be implemented if needed
        };
        
        RemoteConnectionFactory factory = new RemoteConnectionFactory(
            context,
            connection,
            canvas,
            hideKeyboardAndExtraKeys
        );
        
        remoteConnection = factory.build();
        
        // Create handler
        Runnable setModes = () -> {
            // Set connection modes if needed
        };
        
        handler = new RemoteCanvasHandler(
            context,
            canvas,
            remoteConnection,
            connection,
            setModes
        );
        
        Log.d(TAG, "VNC connection manager initialized");
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
        
        Log.d(TAG, "Connecting to VNC server at " + VNC_HOST + ":" + VNC_PORT);
        
        // Auto-start VNC server if not running
        if (serviceClient != null) {
            commandExecutor.startVNCServerIfNeeded(serviceClient, new LinuxCommandExecutor.CommandCallback() {
                @Override
                public void onSuccess(String output) {
                    Log.d(TAG, "VNC server ready: " + output);
                    // Wait a moment for server to be ready, then connect
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        doConnect();
                    }, 1000); // 1 second delay
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
     * Perform the actual VNC connection.
     */
    private void doConnect() {
        // Check if connection is ready
        if (connection.isReadyForConnection()) {
            // Start connection
            handler.sendEmptyMessage(RemoteClientLibConstants.REINIT_SESSION);
            isConnected = true;
            isPaused = false;
        } else {
            Log.e(TAG, "Connection not ready: missing required parameters");
        }
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
     * Disconnect from VNC server.
     * Call this when OS tab is destroyed.
     */
    public void disconnect() {
        if (!isConnected) {
            return;
        }
        
        Log.d(TAG, "Disconnecting from VNC server");
        
        if (remoteConnection != null) {
            try {
                remoteConnection.disconnect();
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

