package com.termos.app.vnc;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Toast;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Simple VNC viewer implementation for maximum compatibility
 * Uses basic RFB protocol without complex dependencies
 */
public class SimpleVNCViewer extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = "SimpleVNCViewer";

    private SurfaceHolder holder;
    private DrawingThread drawingThread;
    private VNCThread vncThread;

    private String host;
    private int port;
    private String password;
    private VNCListener listener;

    private int screenWidth = 1024;
    private int screenHeight = 768;
    private Bitmap screenBitmap;
    private boolean connected = false;

    public interface VNCListener {
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    public SimpleVNCViewer(Context context) {
        super(context);
        init();
    }

    public SimpleVNCViewer(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        holder = getHolder();
        holder.addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public void setVNCListener(VNCListener listener) {
        this.listener = listener;
    }

    public void connect(String host, int port, String password) {
        this.host = host;
        this.port = port;
        this.password = password;

        disconnect();

        vncThread = new VNCThread();
        vncThread.start();
    }

    public void disconnect() {
        connected = false;

        if (vncThread != null) {
            vncThread.interrupt();
            vncThread = null;
        }

        if (listener != null) {
            listener.onDisconnected();
        }
    }

    public boolean isConnected() {
        return connected;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        drawingThread = new DrawingThread();
        drawingThread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // Handle surface changes if needed
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (drawingThread != null) {
            drawingThread.interrupt();
            drawingThread = null;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!connected || vncThread == null) return false;

        int action = event.getAction();
        float x = event.getX();
        float y = event.getY();

        // Convert screen coordinates to VNC coordinates
        int vncX = (int) (x * screenWidth / getWidth());
        int vncY = (int) (y * screenHeight / getHeight());

        vncThread.sendPointerEvent(action, vncX, vncY);

        return true;
    }

    private class VNCThread extends Thread {
        private Socket socket;
        private DataInputStream input;
        private DataOutputStream output;

        @Override
        public void run() {
            try {
                Log.d(TAG, "Connecting to VNC server: " + host + ":" + port);

                // Connect to VNC server
                socket = new Socket(host, port);
                input = new DataInputStream(socket.getInputStream());
                output = new DataOutputStream(socket.getOutputStream());

                // Read protocol version
                byte[] versionBuffer = new byte[12];
                input.readFully(versionBuffer);
                String version = new String(versionBuffer).trim();
                Log.d(TAG, "VNC version: " + version);

                // Send our version (RFB 3.3)
                String ourVersion = "RFB 003.003\n";
                output.write(ourVersion.getBytes());

                // Read security types
                int numSecurityTypes = input.readUnsignedByte();
                Log.d(TAG, "Number of security types: " + numSecurityTypes);

                if (numSecurityTypes == 0) {
                    // Connection failed
                    input.readInt(); // reason length
                    byte[] reasonBytes = new byte[input.readInt()];
                    input.readFully(reasonBytes);
                    String reason = new String(reasonBytes);
                    throw new IOException("Connection failed: " + reason);
                }

                // Read security types
                byte[] securityTypes = new byte[numSecurityTypes];
                input.readFully(securityTypes);

                // Choose security type (prefer None, then VNC auth)
                byte chosenType = 1; // None
                for (byte type : securityTypes) {
                    if (type == 1) { // None
                        chosenType = 1;
                        break;
                    } else if (type == 2) { // VNC auth
                        chosenType = 2;
                    }
                }

                Log.d(TAG, "Chosen security type: " + chosenType);
                output.writeByte(chosenType);

                if (chosenType == 2) {
                    // VNC authentication
                    handleVNCAuth();
                }

                // Read security result
                int securityResult = input.readInt();
                if (securityResult != 0) {
                    throw new IOException("Security handshake failed");
                }

                // Client initialization
                output.writeByte(1); // Share desktop flag

                // Read server initialization
                screenWidth = input.readUnsignedShort();
                screenHeight = input.readUnsignedShort();
                Log.d(TAG, "Screen size: " + screenWidth + "x" + screenHeight);

                // Skip pixel format and name
                input.skipBytes(16); // pixel format
                int nameLength = input.readInt();
                input.skipBytes(nameLength); // name

                // Create screen bitmap
                screenBitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.RGB_565);

                connected = true;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (listener != null) {
                        listener.onConnected();
                    }
                });

                // Start framebuffer update loop
                requestFramebufferUpdate();

                // Main VNC protocol loop
                while (connected && !isInterrupted()) {
                    try {
                        int messageType = input.readUnsignedByte();

                        switch (messageType) {
                            case 0: // FramebufferUpdate
                                handleFramebufferUpdate();
                                break;
                            case 1: // SetColourMapEntries
                                handleSetColourMapEntries();
                                break;
                            case 2: // Bell
                                // Ignore bell
                                break;
                            case 3: // ServerCutText
                                handleServerCutText();
                                break;
                            default:
                                Log.w(TAG, "Unknown message type: " + messageType);
                                break;
                        }
                    } catch (IOException e) {
                        if (connected) {
                            Log.e(TAG, "Error in VNC protocol loop", e);
                        }
                        break;
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "VNC connection error", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (listener != null) {
                        listener.onError(e.getMessage());
                    }
                });
            } finally {
                disconnect();
            }
        }

        private void handleVNCAuth() throws IOException {
            // Read challenge
            byte[] challenge = new byte[16];
            input.readFully(challenge);

            // Simple DES encryption (simplified for demo)
            // In a real implementation, you'd use proper VNC auth
            byte[] response = new byte[16];

            // For now, just send the password as-is (not secure!)
            // This is a simplified implementation
            System.arraycopy(password.getBytes(), 0, response, 0,
                           Math.min(password.length(), 16));

            output.write(response);
        }

        private void requestFramebufferUpdate() throws IOException {
            output.writeByte(3); // FramebufferUpdateRequest
            output.writeByte(0); // Incremental
            output.writeShort(0); // x
            output.writeShort(0); // y
            output.writeShort((short) screenWidth); // width
            output.writeShort((short) screenHeight); // height
        }

        private void handleFramebufferUpdate() throws IOException {
            input.skipBytes(1); // padding
            int numRectangles = input.readUnsignedShort();

            for (int i = 0; i < numRectangles; i++) {
                int x = input.readUnsignedShort();
                int y = input.readUnsignedShort();
                int width = input.readUnsignedShort();
                int height = input.readUnsignedShort();
                int encoding = input.readInt();

                if (encoding == 0) { // Raw encoding
                    handleRawEncoding(x, y, width, height);
                } else {
                    // Skip unknown encodings
                    int dataSize = width * height * 4; // Assume 32-bit
                    input.skipBytes(dataSize);
                }
            }

            // Request next update
            if (connected) {
                requestFramebufferUpdate();
            }
        }

        private void handleRawEncoding(int x, int y, int width, int height) throws IOException {
            int[] pixels = new int[width * height];

            for (int i = 0; i < pixels.length; i++) {
                int b = input.readUnsignedByte();
                int g = input.readUnsignedByte();
                int r = input.readUnsignedByte();
                pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }

            // Update bitmap
            screenBitmap.setPixels(pixels, 0, width, x, y, width, height);

            // Trigger redraw
            new Handler(Looper.getMainLooper()).post(() -> {
                if (drawingThread != null) {
                    drawingThread.requestRedraw();
                }
            });
        }

        private void handleSetColourMapEntries() throws IOException {
            // Skip colour map entries
            input.skipBytes(5); // padding + firstColour
            int numColours = input.readUnsignedShort();
            input.skipBytes(numColours * 6); // RGB triples
        }

        private void handleServerCutText() throws IOException {
            input.skipBytes(3); // padding
            int length = input.readInt();
            input.skipBytes(length); // text
        }

        public void sendPointerEvent(int action, int x, int y) {
            if (!connected || output == null) return;

            try {
                output.writeByte(5); // PointerEvent
                output.writeByte(getButtonMask(action)); // button mask
                output.writeShort(x); // x
                output.writeShort(y); // y
            } catch (IOException e) {
                Log.e(TAG, "Error sending pointer event", e);
            }
        }

        private int getButtonMask(int action) {
            switch (action & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_DOWN:
                    return 1; // Left button
                case MotionEvent.ACTION_UP:
                    return 0; // No buttons
                default:
                    return 0;
            }
        }

        private void disconnect() {
            connected = false;

            try {
                if (input != null) input.close();
                if (output != null) output.close();
                if (socket != null) socket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing VNC connection", e);
            }
        }
    }

    private class DrawingThread extends Thread {
        private boolean redrawRequested = false;

        @Override
        public void run() {
            while (!isInterrupted()) {
                try {
                    synchronized (this) {
                        if (!redrawRequested) {
                            wait();
                        }
                        redrawRequested = false;
                    }

                    if (screenBitmap != null) {
                        Canvas canvas = holder.lockCanvas();
                        if (canvas != null) {
                            // Scale bitmap to fit view
                            Rect srcRect = new Rect(0, 0, screenBitmap.getWidth(), screenBitmap.getHeight());
                            Rect dstRect = new Rect(0, 0, getWidth(), getHeight());

                            Paint paint = new Paint();
                            paint.setFilterBitmap(true);

                            canvas.drawColor(Color.BLACK);
                            canvas.drawBitmap(screenBitmap, srcRect, dstRect, paint);

                            holder.unlockCanvasAndPost(canvas);
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        public void requestRedraw() {
            synchronized (this) {
                redrawRequested = true;
                notify();
            }
        }
    }
}