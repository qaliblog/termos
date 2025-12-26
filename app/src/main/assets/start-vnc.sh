#!/bin/sh
# Termos VNC startup script
# - Starts Lomiri
# - Saves VNC port to a file
# - Auto-restarts VNC if it dies

VNC_RESOLUTION="1024x768"
VNC_DEPTH="24"

VNC_DIR="$HOME/.vnc"
VNC_PORT_FILE="$VNC_DIR/termos_vnc_port"
VNC_XSTARTUP="$VNC_DIR/xstartup"
VNC_LOG="$VNC_DIR/vncserver.log"

mkdir -p "$VNC_DIR"

start_vnc() {
    # Find a free display / port
    DISPLAY_NUM=1
    while true; do
        PORT=$((5900 + DISPLAY_NUM))
        if ! netstat -tln 2>/dev/null | grep -q ":$PORT "; then
            break
        fi
        DISPLAY_NUM=$((DISPLAY_NUM + 1))
    done

    DISPLAY=":$DISPLAY_NUM"

    # Save port so Android OS tab knows where to connect
    echo "$PORT" > "$VNC_PORT_FILE"

    # Lomiri xstartup
    cat > "$VNC_XSTARTUP" << EOF
#!/bin/sh
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
export XDG_SESSION_TYPE=x11
exec lomiri
EOF

    chmod +x "$VNC_XSTARTUP"

    # Kill any existing VNC servers (safe cleanup)
    vncserver -kill "$DISPLAY" >/dev/null 2>&1

    # Start VNC
    vncserver "$DISPLAY" \
        -geometry "$VNC_RESOLUTION" \
        -depth "$VNC_DEPTH" \
        -localhost no \
        -SecurityTypes None \
        -xstartup "$VNC_XSTARTUP" \
        >> "$VNC_LOG" 2>&1

    echo "[VNC] Started Lomiri on display $DISPLAY (port $PORT)" >> "$VNC_LOG"
}

# ------------------------------------------------------------------
# Initial start
# ------------------------------------------------------------------
start_vnc

# ------------------------------------------------------------------
# Watchdog loop (auto-restart)
# ------------------------------------------------------------------
while true; do
    sleep 5

    # If port file vanished, restart
    if [ ! -f "$VNC_PORT_FILE" ]; then
        echo "[VNC] Port file missing, restarting VNC" >> "$VNC_LOG"
        start_vnc
        continue
    fi

    PORT=$(cat "$VNC_PORT_FILE")

    # If nothing is listening on the saved port, restart
    if ! netstat -tln 2>/dev/null | grep -q ":$PORT "; then
        echo "[VNC] Port $PORT not listening, restarting VNC" >> "$VNC_LOG"
        vncserver -kill :* >/dev/null 2>&1
        sleep 1
        start_vnc
    fi
done
