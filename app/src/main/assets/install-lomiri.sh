#!/bin/bash
# Manual Lomiri installation script
# Run this script to install Lomiri desktop environment on Ubuntu/Debian

set -e

echo "=== Lomiri Installation Script ==="
echo "This script will attempt to install Lomiri (Ubuntu Touch desktop environment)"
echo ""

# Check if running on Ubuntu/Debian
if ! command -v apt-get >/dev/null 2>&1; then
    echo "Error: This script requires apt-get (Ubuntu/Debian)"
    exit 1
fi

# Fix dpkg if needed
echo "[1/8] Fixing dpkg configuration..."
dpkg --configure -a 2>&1 || true

# Update package lists
echo "[2/8] Updating package lists..."
apt-get update -qq || {
    echo "Failed to update, fixing and retrying..."
    dpkg --configure -a 2>&1 || true
    apt-get update -qq
}

# Detect Ubuntu version
UBUNTU_VERSION=$(lsb_release -cs 2>/dev/null || echo "jammy")
echo "Detected Ubuntu version: $UBUNTU_VERSION"

# Add Ubuntu Touch repositories for Lomiri
echo "[3/8] Adding Ubuntu Touch repositories..."
if [ ! -f /etc/apt/sources.list.d/ubports.list ]; then
    # Add UBports repository for Lomiri and Ubuntu Touch packages
    echo "deb http://repo.ubports.com/ $UBUNTU_VERSION main" > /etc/apt/sources.list.d/ubports.list
    echo "deb http://repo.ubports.com/ $UBUNTU_VERSION devel" >> /etc/apt/sources.list.d/ubports.list
    echo "UBports repository added"

    # Add Ubuntu Touch PPA if available
    if command -v add-apt-repository >/dev/null 2>&1; then
        add-apt-repository -y ppa:ubports-developers/ppa 2>/dev/null || true
        echo "Ubuntu Touch PPA added"
    fi

    # Try to add GPG keys for UBports
    echo "Adding GPG keys..."
    apt-key adv --keyserver keyserver.ubuntu.com --recv-keys 0E61A7B277ED8A82 2>/dev/null || {
        echo "Warning: Could not add UBports GPG key"
    }

    # Add additional Lomiri repositories based on Ubuntu version
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        case "$VERSION_ID" in
            "20.04")
                echo "deb http://archive.ubuntu.com/ubuntu focal-proposed main universe" > /etc/apt/sources.list.d/lomiri-focal.list
                ;;
            "22.04"|"24.04")
                # For newer Ubuntu versions, try to add Lomiri repos if available
                echo "deb http://archive.ubuntu.com/ubuntu $UBUNTU_VERSION-proposed main universe" > /etc/apt/sources.list.d/lomiri-$UBUNTU_VERSION.list 2>/dev/null || true
                ;;
        esac
    fi

    # Update package lists
    apt-get update -qq 2>/dev/null || {
        echo "Warning: Failed to update repositories, continuing anyway..."
    }
else
    echo "UBports repository already exists"
fi

# Try to install Lomiri
echo "[4/8] Attempting to install Lomiri..."
LOMIRI_INSTALLED=0

# Method 1: Try from UBports repo
if apt-get install -y -qq lomiri-session 2>/dev/null; then
    LOMIRI_INSTALLED=1
    echo "✓ Lomiri installed from UBports repository"
# Method 2: Try unity8-desktop-session-mir
elif apt-get install -y -qq unity8-desktop-session-mir 2>/dev/null; then
    LOMIRI_INSTALLED=1
    echo "✓ Unity8/Lomiri installed"
# Method 3: Try individual Lomiri components
elif apt-get install -y -qq lomiri 2>/dev/null; then
    LOMIRI_INSTALLED=1
    echo "✓ Lomiri base installed"
# Method 4: Try installing from Ubuntu proposed/universe
elif apt-get install -y -qq --allow-unauthenticated lomiri-session 2>/dev/null; then
    LOMIRI_INSTALLED=1
    echo "✓ Lomiri installed (unauthenticated)"
else
    echo "✗ Lomiri packages not found in repositories"
    echo ""
    echo "Attempting alternative installation methods..."

    # Try to install development versions
    if apt-get install -y -qq lomiri-session/unstable 2>/dev/null; then
        LOMIRI_INSTALLED=1
        echo "✓ Lomiri development version installed"
    # Try to install build dependencies and attempt installation again
    elif apt-get install -y -qq build-essential cmake qtbase5-dev qtdeclarative5-dev \
            qml-module-qtquick2 libqt5gui5 libqt5qml5 libqt5quick5 \
            libmirclient-dev libmircommon-dev libgsettings-qt-dev \
            libaccountsservice-dev libunity-api-dev 2>/dev/null; then
        echo "Build dependencies installed, trying Lomiri installation again..."
        if apt-get install -y -qq lomiri-session 2>/dev/null; then
            LOMIRI_INSTALLED=1
            echo "✓ Lomiri installed after installing dependencies"
        fi
    fi

    if [ $LOMIRI_INSTALLED -eq 0 ]; then
        echo ""
        echo "Lomiri installation failed. Options:"
        echo "1. Check if your Ubuntu version is supported by UBports"
        echo "2. Try building from source: https://github.com/ubports/lomiri"
        echo "3. Use XFCE as an alternative: apt-get install -y xfce4"
        echo ""
        echo "Continuing with XFCE fallback..."
        if apt-get install -y -qq xfce4 xfce4-goodies 2>/dev/null; then
            echo "✓ XFCE installed as fallback desktop"
        fi
    fi
fi

# Install Mir and Xwayland
if [ $LOMIRI_INSTALLED -eq 1 ]; then
    echo "[5/8] Installing Mir display server and Xwayland..."
    apt-get install -y -qq mir mir-graphics-drivers-mesa xwayland 2>/dev/null || {
        echo "Some Mir packages failed, trying individual packages..."
        apt-get install -y -qq mir 2>/dev/null || true
        apt-get install -y -qq xwayland 2>/dev/null || true
    }
    echo "✓ Mir and Xwayland installed"
fi

# Install VNC server
echo "[6/8] Installing VNC server..."
apt-get install -y -qq tigervnc-standalone-server tigervnc-common 2>/dev/null || {
    apt-get install -y -qq tightvncserver 2>/dev/null || {
        apt-get install -y -qq x11vnc 2>/dev/null || true
    }
}
echo "✓ VNC server installed"

# Install D-Bus
echo "[7/8] Installing D-Bus utilities..."
apt-get install -y -qq dbus-x11 2>/dev/null || true
echo "✓ D-Bus utilities installed"

# Create/update xstartup script
echo "[8/8] Creating VNC xstartup script..."
mkdir -p /root/.vnc

if [ $LOMIRI_INSTALLED -eq 1 ]; then
    cat > /root/.vnc/xstartup << 'EOF'
#!/bin/bash
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
export DISPLAY=${DISPLAY:-:1}
export USER=root
export HOME=/root

echo "Lomiri xstartup script executing at $(date)" > /tmp/xstartup.log 2>&1

# Mount /proc if not already mounted
if ! mountpoint -q /proc 2>/dev/null; then
    if [ -d /proc ] && [ -r /proc/version ] 2>/dev/null; then
        mount --bind /proc /proc 2>/dev/null || true
    else
        mount -t proc proc /proc 2>/dev/null || true
    fi
fi

# Load X resources if available
[ -r $HOME/.Xresources ] && xrdb $HOME/.Xresources 2>/dev/null || true

# Wait for X server to be ready
sleep 2

# Start D-Bus session daemon
if [ -z "$DBUS_SESSION_BUS_ADDRESS" ]; then
    export TMPDIR=/tmp
    DBUS_OUTPUT=$(dbus-launch --sh-syntax 2>/dev/null || dbus-launch --sh-syntax 2>&1 | grep -v 'shm-helper' || true)
    if [ -n "$DBUS_OUTPUT" ]; then
        eval "$DBUS_OUTPUT" >> /tmp/xstartup.log 2>&1
    fi
fi

# Start Lomiri in X11 mode (for VNC compatibility)
export XDG_SESSION_TYPE=x11
export QT_QUICK_CONTROLS_MOBILE=true
export QT_QUICK_CONTROLS_STYLE=Suru

if command -v lomiri-session >/dev/null 2>&1; then
    echo "Starting Lomiri session..." >> /tmp/xstartup.log 2>&1
    lomiri-session >/tmp/lomiri.log 2>&1 &
    sleep 3
    if pgrep -x lomiri-session >/dev/null 2>&1; then
        echo "Lomiri started successfully" >> /tmp/xstartup.log 2>&1
    else
        echo "Lomiri failed to start, check /tmp/lomiri.log" >> /tmp/xstartup.log 2>&1
    fi
else
    echo "lomiri-session not found" >> /tmp/xstartup.log 2>&1
    xterm -fa "DejaVu Sans Mono" -fs 14 &
fi

# Keep script running
while true; do
    sleep 60
    if ! pgrep -x lomiri-session >/dev/null 2>&1 && command -v lomiri-session >/dev/null 2>&1; then
        echo "Lomiri not running, attempting restart..." >> /tmp/xstartup.log 2>&1
        lomiri-session >/tmp/lomiri-restart.log 2>&1 &
    fi
done
EOF
    chmod +x /root/.vnc/xstartup
    echo "✓ Lomiri xstartup script created"
else
    echo "⚠ Lomiri not installed, xstartup script not updated"
    echo "You can manually edit /root/.vnc/xstartup after installing Lomiri"
fi

echo ""
echo "=== Installation Complete ==="
if [ $LOMIRI_INSTALLED -eq 1 ]; then
    echo "✓ Lomiri is installed and configured"
    echo "✓ Desktop environment ready for VNC"
    echo ""
    echo "To start manually:"
    echo "  /usr/local/bin/start-vnc.sh"
    echo "Or use the OS tab in the Termos app"
else
    echo "⚠ Lomiri installation was not successful"
    echo "XFCE or another desktop environment may be available as fallback"
    echo ""
    echo "You can still use VNC with available desktop environments"
    echo "Or try building Lomiri from source:"
    echo "  https://github.com/ubports/lomiri"
fi
