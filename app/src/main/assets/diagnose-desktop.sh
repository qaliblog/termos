#!/bin/bash
# Desktop environment diagnostic script
# Run this to check why the OS tab shows a blank screen

echo "=== Desktop Environment Diagnostic ==="
echo ""

echo "=== 1. Check /proc mount ==="
if mountpoint -q /proc 2>/dev/null; then
    echo "✓ /proc is mounted"
else
    echo "✗ /proc is NOT mounted"
    echo "  Attempting to mount /proc..."
    if [ -d /proc ] && [ -r /proc/version ] 2>/dev/null; then
        mount --bind /proc /proc 2>/dev/null && echo "  ✓ /proc mounted successfully" || echo "  ✗ Failed to mount /proc"
    else
        mount -t proc proc /proc 2>/dev/null && echo "  ✓ /proc mounted successfully" || echo "  ✗ Failed to mount /proc"
    fi
fi
echo ""

echo "=== 2. Check VNC server ==="
if pgrep -x Xvnc >/dev/null 2>&1; then
    echo "✓ Xvnc is running"
    pgrep -x Xvnc | while read pid; do
        echo "  PID: $pid"
        ps -p $pid -o args= 2>/dev/null | head -1
    done
else
    echo "✗ Xvnc is NOT running"
fi
echo ""

echo "=== 3. Check desktop processes ==="
DESKTOP_FOUND=0

# Check Lomiri
if pgrep -x lomiri-session >/dev/null 2>&1; then
    echo "✓ Lomiri is running"
    DESKTOP_FOUND=1
    pgrep -x lomiri-session | while read pid; do
        echo "  Lomiri PID: $pid"
    done
fi

# Check XFCE
if pgrep -x xfwm4 >/dev/null 2>&1 || pgrep -x xfce4-session >/dev/null 2>&1 || pgrep -f startxfce4 >/dev/null 2>&1; then
    echo "✓ XFCE is running"
    DESKTOP_FOUND=1
    pgrep -x xfwm4 | while read pid; do
        echo "  xfwm4 PID: $pid"
    done
    pgrep -x xfce4-session | while read pid; do
        echo "  xfce4-session PID: $pid"
    done
fi

if [ $DESKTOP_FOUND -eq 0 ]; then
    echo "✗ No desktop environment is running"
fi
echo ""

echo "=== 4. Check installed desktop packages ==="
if command -v dpkg >/dev/null 2>&1; then
    echo "Lomiri packages:"
    dpkg -l | grep -i lomiri || echo "  None installed"
    echo ""
    echo "XFCE packages:"
    dpkg -l | grep -i xfce | head -5 || echo "  None installed"
else
    echo "dpkg not available"
fi
echo ""

echo "=== 5. Check xstartup script ==="
if [ -f /root/.vnc/xstartup ]; then
    echo "✓ xstartup script exists"
    if [ -x /root/.vnc/xstartup ]; then
        echo "✓ xstartup script is executable"
    else
        echo "✗ xstartup script is NOT executable"
        echo "  Fixing permissions..."
        chmod +x /root/.vnc/xstartup 2>/dev/null && echo "  ✓ Fixed" || echo "  ✗ Failed"
    fi
    echo "  First 10 lines:"
    head -10 /root/.vnc/xstartup | sed 's/^/  /'
else
    echo "✗ xstartup script does NOT exist"
fi
echo ""

echo "=== 6. Check xstartup logs ==="
if [ -f /tmp/xstartup.log ]; then
    echo "✓ xstartup.log exists"
    echo "  Last 20 lines:"
    tail -20 /tmp/xstartup.log | sed 's/^/  /'
else
    echo "✗ xstartup.log does NOT exist (xstartup may not have run)"
fi
echo ""

echo "=== 7. Check X server ==="
if [ -n "$DISPLAY" ]; then
    echo "DISPLAY is set to: $DISPLAY"
    if command -v xdpyinfo >/dev/null 2>&1; then
        if xdpyinfo >/dev/null 2>&1; then
            echo "✓ X server is accessible"
            xdpyinfo | head -5 | sed 's/^/  /'
        else
            echo "✗ X server is NOT accessible"
        fi
    fi
else
    echo "✗ DISPLAY is not set"
    echo "  Setting DISPLAY=:1..."
    export DISPLAY=:1
fi
echo ""

echo "=== 8. Check VNC ports ==="
for port in 5901 5902 5903; do
    if command -v ss >/dev/null 2>&1; then
        if ss -ln 2>/dev/null | grep -q ":$port "; then
            echo "✓ Port $port is listening"
        fi
    elif command -v netstat >/dev/null 2>&1; then
        if netstat -ln 2>/dev/null | grep -q ":$port "; then
            echo "✓ Port $port is listening"
        fi
    fi
done
echo ""

echo "=== 9. Check VNC startup script ==="
VNC_START_SCRIPT=""
if [ -f /usr/local/bin/start-vnc.sh ]; then
    echo "✓ /usr/local/bin/start-vnc.sh exists"
    if [ -x /usr/local/bin/start-vnc.sh ]; then
        echo "✓ Script is executable"
        VNC_START_SCRIPT="/usr/local/bin/start-vnc.sh"
    else
        echo "✗ Script is NOT executable"
        chmod +x /usr/local/bin/start-vnc.sh 2>/dev/null && echo "  ✓ Fixed permissions" || echo "  ✗ Failed to fix"
        VNC_START_SCRIPT="/usr/local/bin/start-vnc.sh"
    fi
elif [ -f "$PREFIX/local/bin/start-vnc.sh" ]; then
    echo "✓ $PREFIX/local/bin/start-vnc.sh exists"
    VNC_START_SCRIPT="$PREFIX/local/bin/start-vnc.sh"
else
    echo "✗ VNC startup script not found"
    echo "  Checked: /usr/local/bin/start-vnc.sh"
    echo "  Checked: $PREFIX/local/bin/start-vnc.sh"
fi
echo ""

echo "=== 10. Recommendations ==="
if ! mountpoint -q /proc 2>/dev/null; then
    echo "⚠ Mount /proc: mount -t proc proc /proc"
fi

if ! pgrep -x Xvnc >/dev/null 2>&1; then
    echo "⚠ VNC server is NOT running"
    if [ -n "$VNC_START_SCRIPT" ] && [ -x "$VNC_START_SCRIPT" ]; then
        echo "  To start VNC server, run:"
        echo "    bash $VNC_START_SCRIPT"
        echo ""
        read -p "  Would you like to start VNC server now? (y/n) " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            echo "  Starting VNC server..."
            bash "$VNC_START_SCRIPT" >/tmp/vnc-start.log 2>&1 &
            sleep 3
            if pgrep -x Xvnc >/dev/null 2>&1; then
                echo "  ✓ VNC server started successfully"
            else
                echo "  ✗ VNC server failed to start. Check /tmp/vnc-start.log"
            fi
        fi
    else
        echo "  VNC startup script not found. You may need to:"
        echo "    1. Reinstall OS (Install OS button in app)"
        echo "    2. Or manually start Xvnc: Xvnc :1 -geometry 1280x720 -depth 24 -SecurityTypes None &"
    fi
fi

if [ $DESKTOP_FOUND -eq 0 ] && pgrep -x Xvnc >/dev/null 2>&1; then
    echo "⚠ Desktop not running (but VNC is running). Options:"
    if command -v lomiri-session >/dev/null 2>&1; then
        echo "  - Try Lomiri: export DISPLAY=:1 && lomiri-session &"
    fi
    if command -v startxfce4 >/dev/null 2>&1; then
        echo "  - Try XFCE: export DISPLAY=:1 && startxfce4 &"
    fi
    if [ -x /root/.vnc/xstartup ]; then
        echo "  - Run xstartup: export DISPLAY=:1 && /root/.vnc/xstartup &"
        echo ""
        read -p "  Would you like to run xstartup now? (y/n) " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            export DISPLAY=:1
            /root/.vnc/xstartup >/tmp/xstartup-manual.log 2>&1 &
            sleep 3
            if pgrep -x xfwm4 >/dev/null 2>&1 || pgrep -x xfce4-session >/dev/null 2>&1 || pgrep -x lomiri-session >/dev/null 2>&1; then
                echo "  ✓ Desktop started successfully"
            else
                echo "  ✗ Desktop failed to start. Check /tmp/xstartup-manual.log"
            fi
        fi
    fi
fi
echo ""

echo "=== Diagnostic Complete ==="
