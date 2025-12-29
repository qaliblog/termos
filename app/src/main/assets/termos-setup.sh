#!/bin/sh
set -e

SETUP_MARKER="/root/.termos-setup-complete"
SETUP_LOG="/root/.termos-setup.log"
FORCE_SETUP=false

# Parse arguments
if [ "$1" = "--force" ]; then
    FORCE_SETUP=true
    rm -f "$SETUP_MARKER"
fi

# Check if already completed
if [ -f "$SETUP_MARKER" ] && [ "$FORCE_SETUP" = false ]; then
    echo "Setup already completed. Use --force to re-run." >&2
    exit 0
fi

# Detect distribution
detect_distro() {
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        echo "$ID"
    elif [ -f /etc/alpine-release ]; then
        echo "alpine"
    else
        echo "unknown"
    fi
}

# Logging function
log() {
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] $*" | tee -a "$SETUP_LOG"
}

log "Starting termos setup..."

DISTRO=$(detect_distro)
log "Detected distribution: $DISTRO"

# Install based on distribution
case "$DISTRO" in
    ubuntu|debian)
        log "Using apt package manager"
        
        # Update package lists
        log "Updating package lists..."
        apt-get update -qq || {
            log "ERROR: Failed to update package lists"
            exit 1
        }
        
        # Install Chromium
        log "Installing Chromium..."
        apt-get install -y chromium-browser chromium-chromedriver || {
            log "WARNING: chromium-chromedriver not available, will use webdriver-manager"
            apt-get install -y chromium-browser
        }
        
        # Install Python + pip
        log "Installing Python 3 and pip..."
        apt-get install -y python3 python3-pip python3-venv || {
            log "ERROR: Failed to install Python"
            exit 1
        }
        
        # Install Selenium and webdriver-manager
        log "Installing Python packages (selenium, webdriver-manager)..."
        pip3 install --break-system-packages selenium webdriver-manager || {
            # Fallback without --break-system-packages for older pip
            pip3 install selenium webdriver-manager || {
                log "ERROR: Failed to install Python packages"
                exit 1
            }
        }
        
        # Install PCManFM
        log "Installing PCManFM file manager..."
        apt-get install -y pcmanfm || {
            log "WARNING: Failed to install PCManFM, trying alternative..."
            apt-get install -y thunar || {
                log "ERROR: Failed to install file manager"
                exit 1
            }
        }
        
        # Install VNC + X11 + Desktop
        log "Installing TightVNC server and desktop environment..."
        apt-get install -y \
            tightvncserver \
            xvfb \
            x11vnc \
            xfce4 \
            xfce4-goodies \
            dbus-x11 || {
            log "ERROR: Failed to install VNC/desktop packages"
            exit 1
        }
        ;;
        
    alpine)
        log "Using apk package manager"
        
        # Update package lists
        log "Updating package lists..."
        apk update || {
            log "ERROR: Failed to update package lists"
            exit 1
        }
        
        # Install Chromium
        log "Installing Chromium..."
        apk add chromium chromium-chromedriver || {
            log "WARNING: chromium-chromedriver not available"
            apk add chromium
        }
        
        # Install Python + pip
        log "Installing Python 3 and pip..."
        apk add python3 py3-pip || {
            log "ERROR: Failed to install Python"
            exit 1
        }
        
        # Install Selenium and webdriver-manager
        log "Installing Python packages..."
        pip3 install selenium webdriver-manager || {
            log "ERROR: Failed to install Python packages"
            exit 1
        }
        
        # Install PCManFM
        log "Installing PCManFM..."
        apk add pcmanfm || {
            log "WARNING: PCManFM not available, trying alternative..."
            apk add thunar || {
                log "ERROR: Failed to install file manager"
                exit 1
            }
        }
        
        # Install VNC + X11 + Desktop
        log "Installing TightVNC server and desktop environment..."
        apk add \
            tightvnc \
            xvfb \
            x11vnc \
            xfce4 \
            xfce4-terminal \
            dbus || {
            log "ERROR: Failed to install VNC/desktop packages"
            exit 1
        }
        ;;
        
    *)
        log "ERROR: Unsupported distribution: $DISTRO"
        exit 1
        ;;
esac

# Verify installations
log "Verifying installations..."

# Check Chromium
if command -v chromium >/dev/null 2>&1 || command -v chromium-browser >/dev/null 2>&1; then
    log "✓ Chromium installed"
else
    log "ERROR: Chromium not found"
    exit 1
fi

# Check Python
if command -v python3 >/dev/null 2>&1; then
    PYTHON_VERSION=$(python3 --version 2>&1)
    log "✓ Python installed: $PYTHON_VERSION"
else
    log "ERROR: Python3 not found"
    exit 1
fi

# Check Selenium
if python3 -c "import selenium" 2>/dev/null; then
    SELENIUM_VERSION=$(python3 -c "import selenium; print(selenium.__version__)" 2>/dev/null)
    log "✓ Selenium installed: $SELENIUM_VERSION"
else
    log "ERROR: Selenium not found"
    exit 1
fi

# Check webdriver-manager
if python3 -c "import webdriver_manager" 2>/dev/null; then
    log "✓ webdriver-manager installed"
else
    log "ERROR: webdriver-manager not found"
    exit 1
fi

# Check file manager
if command -v pcmanfm >/dev/null 2>&1 || command -v thunar >/dev/null 2>&1; then
    log "✓ File manager installed"
else
    log "ERROR: File manager not found"
    exit 1
fi

# Check VNC server
if command -v vncserver >/dev/null 2>&1 || command -v Xvnc >/dev/null 2>&1; then
    log "✓ VNC server installed"
else
    log "ERROR: VNC server not found"
    exit 1
fi

# Mark setup as complete
touch "$SETUP_MARKER"
log "Setup completed successfully!"

# Create helper script for VNC server management
cat > /usr/local/bin/vnc-status.sh << 'EOF'
#!/bin/sh
# VNC Status Checker for Termos
# Shows information about running VNC servers

echo "=== Termos VNC Status ==="
echo

# Check if in Termos environment
if [ ! -f "/usr/local/bin/start-vnc.sh" ]; then
    echo "Warning: Not running in Termos environment"
    echo
fi

# Method 1: Check listening ports
echo "🔍 Checking listening VNC ports (5900-5999):"
if command -v ss >/dev/null 2>&1; then
    vnc_ports=$(ss -ln | grep -E ":590[0-9]" | wc -l)
    if [ "$vnc_ports" -gt 0 ]; then
        echo "  ✓ Found $vnc_ports listening VNC port(s):"
        ss -ln | grep -E ":590[0-9]" | while read -r line; do
            port=$(echo "$line" | awk '{print $4}' | sed 's/.*://')
            if [ -n "$port" ] && [ "$port" -ge 5900 ] && [ "$port" -le 5999 ]; then
                display=$((port - 5900))
                echo "    Display :$display → Port $port"
            fi
        done
    else
        echo "  ✗ No listening VNC ports found"
    fi
elif command -v netstat >/dev/null 2>&1; then
    vnc_ports=$(netstat -ln | grep -E ":590[0-9]" | wc -l)
    if [ "$vnc_ports" -gt 0 ]; then
        echo "  ✓ Found $vnc_ports listening VNC port(s):"
        netstat -ln | grep -E ":590[0-9]" | while read -r line; do
            port=$(echo "$line" | awk '{print $4}' | sed 's/.*://')
            if [ -n "$port" ] && [ "$port" -ge 5900 ] && [ "$port" -le 5999 ]; then
                display=$((port - 5900))
                echo "    Display :$display → Port $port"
            fi
        done
    else
        echo "  ✗ No listening VNC ports found"
    fi
else
    echo "  ⚠ Cannot check ports (ss/netstat not available)"
fi

echo

# Method 2: Check running processes
echo "🔍 Checking running VNC processes:"
vnc_processes=$(ps aux | grep -E "[v]ncserver|[X]vnc|[x]11vnc" | wc -l)
if [ "$vnc_processes" -gt 0 ]; then
    echo "  ✓ Found $vnc_processes VNC process(es):"
    ps aux | grep -E "[v]ncserver|[X]vnc|[x]11vnc" | while read -r line; do
        pid=$(echo "$line" | awk '{print $2}')
        cmd=$(echo "$line" | awk '{print $11}' | sed 's|.*/||')
        echo "    PID $pid: $cmd"
    done
else
    echo "  ✗ No VNC processes running"
fi

echo

# Method 3: Check vncserver list
echo "🔍 Checking VNC server list:"
if command -v vncserver >/dev/null 2>&1; then
    server_list=$(vncserver -list 2>/dev/null)
    if echo "$server_list" | grep -q '^:'; then
        echo "  ✓ VNC servers managed by vncserver:"
        echo "$server_list" | grep '^:' | while read -r line; do
            echo "    $line"
        done
    else
        echo "  ✗ No servers listed by vncserver"
    fi
else
    echo "  ✗ vncserver command not available"
fi

echo

# Method 4: Check Termos VNC info
echo "🔍 Checking Termos VNC information:"
if [ -f "/tmp/vnc-display.txt" ]; then
    echo "  ✓ Termos VNC info found:"
    cat "/tmp/vnc-display.txt" 2>/dev/null | while read -r line; do
        echo "    $line"
    done
else
    echo "  ✗ No Termos VNC info file found"
fi

echo
echo "💡 Commands:"
echo "  start-vnc.sh --list    List active servers"
echo "  start-vnc.sh --stop    Stop all servers"
echo "  start-vnc.sh           Start new server"
echo
EOF
chmod +x /usr/local/bin/vnc-status.sh

# Create helper script for manual Selenium testing
cat > /usr/local/bin/test-selenium.sh << 'EOF'
#!/bin/sh
python3 << 'PYTHON'
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.chrome.service import Service
from webdriver_manager.chrome import ChromeDriverManager

print("Testing Selenium with Chromium...")

options = Options()
options.binary_location = "/usr/bin/chromium" or "/usr/bin/chromium-browser"
options.add_argument("--headless")
options.add_argument("--no-sandbox")
options.add_argument("--disable-dev-shm-usage")

service = Service(ChromeDriverManager().install())
driver = webdriver.Chrome(service=service, options=options)

try:
    driver.get("https://www.google.com")
    print(f"✓ Successfully loaded page: {driver.title}")
finally:
    driver.quit()

print("Selenium test completed!")
PYTHON
EOF
chmod +x /usr/local/bin/test-selenium.sh
log "Created test-selenium.sh helper script"

exit 0

