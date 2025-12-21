#!/bin/sh
# VNC Server startup script for Termos
# Starts TigerVNC server on display :1 (port 5901)

VNC_DISPLAY=":1"
VNC_PORT="5901"
VNC_RESOLUTION="1024x768"
VNC_DEPTH="24"
VNC_PASSWORD_FILE="$HOME/.vnc/passwd"
VNC_LOG_DIR="$HOME/.vnc"
VNC_XSTARTUP="$HOME/.vnc/xstartup"

# Create VNC directory
mkdir -p "$VNC_LOG_DIR"

# Set VNC password if not set (empty password for localhost)
if [ ! -f "$VNC_PASSWORD_FILE" ]; then
    mkdir -p "$(dirname "$VNC_PASSWORD_FILE")"
    # Create empty password file (no password)
    echo "" | vncpasswd -f > "$VNC_PASSWORD_FILE" 2>/dev/null || {
        # If vncpasswd not available, create empty file
        touch "$VNC_PASSWORD_FILE"
    }
    chmod 600 "$VNC_PASSWORD_FILE"
fi

# Create X startup script if it doesn't exist
if [ ! -f "$VNC_XSTARTUP" ]; then
    cat > "$VNC_XSTARTUP" << 'EOF'
#!/bin/sh
# Lomiri (Ubuntu Touch) startup script for VNC - Touch-based, no mouse hover

# Start D-Bus if not running
if [ -z "$DBUS_SESSION_BUS_ADDRESS" ]; then
    eval $(dbus-launch --sh-syntax)
    export DBUS_SESSION_BUS_ADDRESS
fi

# Set environment for touch-based interaction
export QT_QUICK_CONTROLS_MOBILE=true
export QT_QUICK_CONTROLS_STYLE=Suru
export MIR_SERVER_ENABLE_MIR_CLIENT=1

# Disable mouse hover effects
export QT_X11_NO_MITSHM=1

# Start Lomiri session
if command -v lomiri-session >/dev/null 2>&1; then
    exec lomiri-session
elif command -v unity8-session >/dev/null 2>&1; then
    exec unity8-session
else
    # Fallback: start basic X session with touch-friendly terminal
    exec xterm -fa "DejaVu Sans Mono" -fs 14
fi
EOF
    chmod +x "$VNC_XSTARTUP"
fi

# Kill existing VNC server on this display if running
vncserver -kill "$VNC_DISPLAY" >/dev/null 2>&1 || true

# Start VNC server
echo "Starting VNC server on display $VNC_DISPLAY (port $VNC_PORT)..."
vncserver "$VNC_DISPLAY" \
    -geometry "$VNC_RESOLUTION" \
    -depth "$VNC_DEPTH" \
    -localhost no \
    -SecurityTypes None \
    -xstartup "$VNC_XSTARTUP" \
    > "$VNC_LOG_DIR/vncserver.log" 2>&1

if [ $? -eq 0 ]; then
    echo "VNC server started successfully on $VNC_DISPLAY"
    echo "Connect via: localhost:$VNC_PORT"
else
    echo "Failed to start VNC server. Check logs: $VNC_LOG_DIR/vncserver.log"
    exit 1
fi

