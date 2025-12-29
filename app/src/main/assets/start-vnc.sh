#!/bin/sh
# VNC Server startup script for Termos
# Starts TightVNC server with automatic port incrementing
# Default password: termos123

# Function to list active VNC servers
list_vnc_servers() {
    echo "Active VNC Servers:"
    echo "==================="

    # Method 1: Check listening ports
    echo "Listening ports (5900-5999):"
    if command -v ss >/dev/null 2>&1; then
        ss -ln | grep -E ":590[0-9]" | while read -r line; do
            port=$(echo "$line" | awk '{print $4}' | sed 's/.*://')
            if [ -n "$port" ] && [ "$port" -ge 5900 ] && [ "$port" -le 5999 ]; then
                display=$((port - 5900))
                echo "  Display :$display (Port $port)"
            fi
        done
    elif command -v netstat >/dev/null 2>&1; then
        netstat -ln | grep -E ":590[0-9]" | while read -r line; do
            port=$(echo "$line" | awk '{print $4}' | sed 's/.*://')
            if [ -n "$port" ] && [ "$port" -ge 5900 ] && [ "$port" -le 5999 ]; then
                display=$((port - 5900))
                echo "  Display :$display (Port $port)"
            fi
        done
    fi

    # Method 2: Check running VNC processes
    echo "Running VNC processes:"
    if pgrep -f "[v]ncserver|[X]vnc|[x]11vnc" >/dev/null 2>&1; then
        ps aux | grep -E "[v]ncserver|[X]vnc|[x]11vnc" | while read -r line; do
            pid=$(echo "$line" | awk '{print $2}')
            cmd=$(echo "$line" | awk '{print $11}')
            args=$(echo "$line" | cut -d' ' -f12-)
            echo "  PID $pid: $cmd $args"
        done
    else
        echo "  No VNC processes found"
    fi

    # Method 3: Check vncserver list (TightVNC/TigerVNC specific)
    if command -v vncserver >/dev/null 2>&1; then
        echo "VNC Server list:"
        vncserver -list 2>/dev/null || echo "  No servers listed (or command failed)"
    fi

    # Method 4: Check Termos VNC info file
    if [ -f "/tmp/vnc-display.txt" ]; then
        echo "Termos VNC info:"
        cat "/tmp/vnc-display.txt" 2>/dev/null || echo "  Could not read info file"
    fi
}

# Function to stop VNC servers
stop_vnc_servers() {
    echo "Stopping VNC servers..."

    # Stop all VNC servers listed by vncserver -list
    if command -v vncserver >/dev/null 2>&1; then
        vncserver -list 2>/dev/null | grep '^:' | while read -r line; do
            display=$(echo "$line" | awk '{print $1}')
            echo "Stopping $display..."
            vncserver -kill "$display" >/dev/null 2>&1
        done
    fi

    # Kill any remaining VNC processes
    pkill -f "[v]ncserver|[X]vnc|[x]11vnc" >/dev/null 2>&1 && echo "Killed remaining VNC processes"

    # Clean up temp files
    rm -f /tmp/vnc-display.txt

    echo "VNC servers stopped."
}

# Check command line parameters
case "$1" in
    --list|-l)
        list_vnc_servers
        exit 0
        ;;
    --stop|-s)
        stop_vnc_servers
        exit 0
        ;;
    --help|-h)
        echo "Usage: $0 [OPTIONS]"
        echo ""
        echo "Options:"
        echo "  --list, -l    List active VNC servers"
        echo "  --stop, -s    Stop all VNC servers"
        echo "  --help, -h    Show this help"
        echo ""
        echo "Without options, starts a new VNC server"
        exit 0
        ;;
esac

VNC_BASE_PORT=5901
VNC_RESOLUTION="1024x768"
VNC_DEPTH="24"
VNC_PASSWORD_FILE="$HOME/.vnc/passwd"
VNC_LOG_DIR="$HOME/.vnc"
VNC_XSTARTUP="$HOME/.vnc/xstartup"
VNC_DISPLAY_FILE="/tmp/vnc-display.txt"

# Function to find an available VNC port
find_available_port() {
    local port=$VNC_BASE_PORT
    local max_port=5999

    while [ $port -le $max_port ]; do
        # Check if port is already in use
        if ! (ss -ln 2>/dev/null | grep -q ":$port ") && ! (netstat -ln 2>/dev/null | grep -q ":$port "); then
            echo $port
            return 0
        fi
        port=$((port + 1))
    done

    echo "No available VNC ports found between $VNC_BASE_PORT and $max_port" >&2
    return 1
}

# Create VNC directory
mkdir -p "$VNC_LOG_DIR"

# Set VNC password if not set (use "termos123" as password)
if [ ! -f "$VNC_PASSWORD_FILE" ]; then
    mkdir -p "$(dirname "$VNC_PASSWORD_FILE")"
    # Create password file with "termos123" as password
    # Try TightVNC vncpasswd first, then fall back to TigerVNC
    if command -v vncpasswd >/dev/null 2>&1; then
        echo "termos123" | vncpasswd -f > "$VNC_PASSWORD_FILE" 2>/dev/null || {
            # Fallback: create password file manually for TightVNC
            echo -n "termos123" > "$VNC_PASSWORD_FILE"
            echo "Created password file manually (TightVNC format)"
        }
    else
        # Fallback: create basic password file
        echo -n "termos123" > "$VNC_PASSWORD_FILE"
        echo "Created basic password file (no vncpasswd available)"
    fi
    chmod 600 "$VNC_PASSWORD_FILE"
    echo "VNC password set to 'termos123'"
fi

# Create X startup script if it doesn't exist
if [ ! -f "$VNC_XSTARTUP" ]; then
    cat > "$VNC_XSTARTUP" << 'EOF'
#!/bin/sh
# Lomiri (Ubuntu Touch) startup script for VNC - Touch-based, no mouse hover

# Mount /proc if not already mounted (required for D-Bus and other services)
if ! mountpoint -q /proc 2>/dev/null; then
    if [ -d /proc ] && [ -r /proc/version ] 2>/dev/null; then
        mount --bind /proc /proc 2>/dev/null || true
    else
        mount -t proc proc /proc 2>/dev/null || true
    fi
fi

# Start D-Bus if not running
if [ -z "$DBUS_SESSION_BUS_ADDRESS" ]; then
    # Set TMPDIR to an absolute path to help with shm-helper path resolution
    export TMPDIR=/tmp
    export XDG_RUNTIME_DIR=/tmp

    # Ensure hostname is set properly to avoid VNC hostname issues
    if [ -z "$(hostname 2>/dev/null)" ] || [ "$(hostname)" = "(none)" ]; then
        hostname localhost 2>/dev/null || true
    fi

    # Create /etc/hosts entry if missing
    if [ ! -f /etc/hosts ] || ! grep -q "127.0.0.1.*localhost" /etc/hosts 2>/dev/null; then
        echo "127.0.0.1 localhost" >> /etc/hosts 2>/dev/null || true
    fi

    # Try multiple approaches to start D-Bus, suppressing shm-helper errors
    if command -v dbus-launch >/dev/null 2>&1; then
        # Use --sh-syntax and properly suppress all shm-helper related errors
        DBUS_OUTPUT=$(dbus-launch --sh-syntax --exit-with-session 2>&1 | grep -vE '(shm-helper|expected absolute path|--shm-helper)' | grep -E '(DBUS_SESSION_BUS_ADDRESS|DBUS_SESSION_BUS_PID)' || true)
        if [ -n "$DBUS_OUTPUT" ]; then
            eval "$DBUS_OUTPUT" 2>/dev/null || true
        fi
    fi

    # Fallback: try alternative dbus setup if the above failed
    if [ -z "$DBUS_SESSION_BUS_ADDRESS" ]; then
        export DBUS_SESSION_BUS_ADDRESS="unix:path=/tmp/dbus-session"
        mkdir -p /tmp
        # Only start dbus-daemon if it's not already running
        if ! pgrep -f "dbus-daemon.*session" >/dev/null 2>&1; then
            dbus-daemon --session --address="$DBUS_SESSION_BUS_ADDRESS" --nofork --nopidfile >/dev/null 2>&1 &
            sleep 1
        fi
    fi
fi

# Set environment for touch-based interaction
export QT_QUICK_CONTROLS_MOBILE=true
export QT_QUICK_CONTROLS_STYLE=Suru
export MIR_SERVER_ENABLE_MIR_CLIENT=1

# Disable mouse hover effects
export QT_X11_NO_MITSHM=1

# Set display
export DISPLAY=${DISPLAY:-:1}

# Try to start Lomiri session with Xwayland bridge for VNC compatibility
if command -v lomiri-session >/dev/null 2>&1; then
    # Try X11 mode first (works better with VNC)
    export XDG_SESSION_TYPE=x11
    exec lomiri-session
elif command -v unity8-session >/dev/null 2>&1; then
    export XDG_SESSION_TYPE=x11
    exec unity8-session
else
    # Fallback: start basic X session with touch-friendly terminal
    exec xterm -fa "DejaVu Sans Mono" -fs 14
fi
EOF
    chmod +x "$VNC_XSTARTUP"
fi

# Find an available port
VNC_PORT=$(find_available_port)
if [ $? -ne 0 ]; then
    echo "Failed to find available VNC port"
    exit 1
fi

# Calculate display number from port (port 5901 = display :1, port 5902 = display :2, etc.)
VNC_DISPLAY=":$((VNC_PORT - 5900))"

# Kill existing VNC server on this display if running
vncserver -kill "$VNC_DISPLAY" >/dev/null 2>&1 || true

# Start VNC server
echo "Starting VNC server on display $VNC_DISPLAY (port $VNC_PORT)..."

# Write port information to file BEFORE starting server (for immediate reading)
mkdir -p "$(dirname "$VNC_DISPLAY_FILE")"
echo "display:$VNC_DISPLAY" > "$VNC_DISPLAY_FILE"
echo "port:$VNC_PORT" >> "$VNC_DISPLAY_FILE"
echo "resolution:$VNC_RESOLUTION" >> "$VNC_DISPLAY_FILE"
echo "started:$(date +%s)" >> "$VNC_DISPLAY_FILE"
echo "VNC port information written to $VNC_DISPLAY_FILE"

# Start TightVNC server in background with proper error handling
vncserver "$VNC_DISPLAY" \
    -geometry "$VNC_RESOLUTION" \
    -depth "$VNC_DEPTH" \
    -localhost no \
    -SecurityTypes VncAuth \
    -xstartup "$VNC_XSTARTUP" \
    > "$VNC_LOG_DIR/vncserver_raw.log" 2>&1 &

VNC_PID=$!

# Filter out shm-helper errors from the log in background
(sleep 2 && grep -vE '(shm-helper|expected absolute path|--shm-helper)' "$VNC_LOG_DIR/vncserver_raw.log" > "$VNC_LOG_DIR/vncserver.log") &

# Wait a moment for server to start
sleep 3

if kill -0 $VNC_PID 2>/dev/null; then
    echo "VNC server started successfully on $VNC_DISPLAY (PID: $VNC_PID)"
    echo "Connect via: localhost:$VNC_PORT"
    echo "Password: termos123"
    echo "Server is running in background"

    # Update the file with success status
    echo "status:running" >> "$VNC_DISPLAY_FILE"
    echo "pid:$VNC_PID" >> "$VNC_DISPLAY_FILE"
else
    echo "Failed to start VNC server. Check logs: $VNC_LOG_DIR/vncserver.log"
    # Update the file with failure status
    echo "status:failed" >> "$VNC_DISPLAY_FILE"
    exit 1
fi

