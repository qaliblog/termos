set -e  # Exit immediately on Failure

export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/share/bin:/usr/share/sbin:/usr/local/bin:/usr/local/sbin:/system/bin:/system/xbin
export HOME=/root

if [ ! -s /etc/resolv.conf ]; then
    echo "nameserver 8.8.8.8" > /etc/resolv.conf
fi

export PS1="\[\e[38;5;46m\]\u\[\033[39m\]@reterm \[\033[39m\]\w \[\033[0m\]\\$ "
# shellcheck disable=SC2034
export PIP_BREAK_SYSTEM_PACKAGES=1
export DEBIAN_FRONTEND=noninteractive

# Function to wait for apt lock to be released
wait_for_apt_lock() {
    local max_wait=30  # Maximum wait time in seconds
    local wait_time=0
    local lock_file="/var/lib/apt/lists/lock"
    local dpkg_lock="/var/lib/dpkg/lock"
    local lock_frontend="/var/lib/apt/lists/lock-frontend"
    local dpkg_lock_frontend="/var/lib/dpkg/lock-frontend"
    
    # Try to find process ID holding the lock
    find_lock_pid() {
        local lock="$1"
        local pid=""
        
        # Try lsof first (if available)
        if command -v lsof >/dev/null 2>&1; then
            pid=$(lsof -t "$lock" 2>/dev/null | head -1)
            [ -n "$pid" ] && echo "$pid" && return 0
        fi
        
        # Try fuser (if available)
        if command -v fuser >/dev/null 2>&1; then
            pid=$(fuser "$lock" 2>/dev/null | awk '{print $1}' | head -1)
            [ -n "$pid" ] && echo "$pid" && return 0
        fi
        
        # Check if apt or dpkg processes are running
        if pgrep -x apt-get >/dev/null 2>&1 || pgrep -x apt >/dev/null 2>&1 || pgrep -x dpkg >/dev/null 2>&1; then
            # If apt/dpkg processes are running and lock exists, assume it's locked
            if [ -f "$lock" ]; then
                echo "locked"
                return 0
            fi
        fi
        
        return 1
    }
    
    while [ $wait_time -lt $max_wait ]; do
        # Check if lock files exist
        local has_lock=false
        
        # Check apt lists lock
        if [ -f "$lock_file" ]; then
            local lock_pid=$(find_lock_pid "$lock_file")
            if [ -n "$lock_pid" ] && [ "$lock_pid" != "locked" ]; then
                # Check if process is still running
                if kill -0 "$lock_pid" 2>/dev/null; then
                    has_lock=true
                else
                    # Stale lock - remove it
                    echo -e "\e[33;1m[!] \e[0mRemoving stale apt lock file\e[0m"
                    rm -f "$lock_file" "$lock_frontend" 2>/dev/null || true
                fi
            elif [ "$lock_pid" = "locked" ]; then
                has_lock=true
            else
                # No process found, but lock exists - might be stale
                # Wait a moment to see if it's being created
                sleep 0.5
                if [ -f "$lock_file" ]; then
                    # Still exists, try to remove if it's been there too long
                    local lock_age=$(stat -c %Y "$lock_file" 2>/dev/null || echo "0")
                    local current_time=$(date +%s 2>/dev/null || echo "0")
                    if [ "$current_time" != "0" ] && [ "$lock_age" != "0" ]; then
                        local age=$((current_time - lock_age))
                        if [ $age -gt 10 ]; then
                            # Lock is older than 10 seconds and no process found - likely stale
                            echo -e "\e[33;1m[!] \e[0mRemoving stale apt lock file (age: ${age}s)\e[0m"
                            rm -f "$lock_file" "$lock_frontend" 2>/dev/null || true
                        else
                            has_lock=true
                        fi
                    else
                        has_lock=true
                    fi
                fi
            fi
        fi
        
        # Check dpkg lock
        if [ -f "$dpkg_lock" ]; then
            local dpkg_pid=$(find_lock_pid "$dpkg_lock")
            if [ -n "$dpkg_pid" ] && [ "$dpkg_pid" != "locked" ]; then
                if kill -0 "$dpkg_pid" 2>/dev/null; then
                    has_lock=true
                else
                    echo -e "\e[33;1m[!] \e[0mRemoving stale dpkg lock file\e[0m"
                    rm -f "$dpkg_lock" "$dpkg_lock_frontend" 2>/dev/null || true
                fi
            elif [ "$dpkg_pid" = "locked" ]; then
                has_lock=true
            else
                # Similar stale check for dpkg lock
                sleep 0.5
                if [ -f "$dpkg_lock" ]; then
                    local lock_age=$(stat -c %Y "$dpkg_lock" 2>/dev/null || echo "0")
                    local current_time=$(date +%s 2>/dev/null || echo "0")
                    if [ "$current_time" != "0" ] && [ "$lock_age" != "0" ]; then
                        local age=$((current_time - lock_age))
                        if [ $age -gt 10 ]; then
                            echo -e "\e[33;1m[!] \e[0mRemoving stale dpkg lock file (age: ${age}s)\e[0m"
                            rm -f "$dpkg_lock" "$dpkg_lock_frontend" 2>/dev/null || true
                        else
                            has_lock=true
                        fi
                    else
                        has_lock=true
                    fi
                fi
            fi
        fi
        
        if [ "$has_lock" = false ]; then
            return 0  # Lock is free
        fi
        
        # Wait a bit before checking again
        sleep 1
        wait_time=$((wait_time + 1))
    done
    
    # If we get here, we've timed out
    echo -e "\e[33;1m[!] \e[0mWarning: Apt lock wait timeout. Attempting to remove stale locks.\e[0m"
    # Try to remove locks one more time
    rm -f "$lock_file" "$lock_frontend" "$dpkg_lock" "$dpkg_lock_frontend" 2>/dev/null || true
    return 1
}

# Function to safely run apt-get commands
safe_apt_get() {
    local retries=2
    local attempt=0
    local stderr_file="/tmp/apt_stderr_$$"
    
    while [ $attempt -lt $retries ]; do
        wait_for_apt_lock || true
        
        # Run apt-get, capturing stderr to check for lock errors
        # stdout goes through normally (respects -qq flags)
        apt-get "$@" 2> "$stderr_file"
        local exit_code=$?
        
        # Check stderr for lock errors
        if grep -qE "(Could not get lock|Unable to lock|is held by process)" "$stderr_file" 2>/dev/null; then
            # Show the lock error to user
            cat "$stderr_file" >&2
            rm -f "$stderr_file"
            
            if [ $attempt -eq 0 ]; then
                echo -e "\e[33;1m[!] \e[0mWaiting for apt lock to be released and retrying...\e[0m" >&2
                sleep 3
                attempt=$((attempt + 1))
                continue
            else
                echo -e "\e[31;1m[!] \e[0mFailed to acquire apt lock after retries. Skipping this operation.\e[0m" >&2
                return 1
            fi
        else
            # No lock error - show stderr normally and return exit code
            cat "$stderr_file" >&2
            rm -f "$stderr_file"
            return $exit_code
        fi
    done
    
    rm -f "$stderr_file"
    return 1
}

# Update package lists and upgrade system
echo -e "\e[34;1m[*] \e[0mUpdating package lists\e[0m"
safe_apt_get update -qq || true

# Check and install essential packages
required_packages="bash nano curl wget"
missing_packages=""
for pkg in $required_packages; do
    if ! dpkg -l | grep -q "^ii.*$pkg "; then
        missing_packages="$missing_packages $pkg"
    fi
done

if [ -n "$missing_packages" ]; then
    echo -e "\e[34;1m[*] \e[0mInstalling Important packages\e[0m"
    safe_apt_get update -qq || true
    safe_apt_get upgrade -y -qq || true
    safe_apt_get install -y -qq $missing_packages
    if [ $? -eq 0 ]; then
        echo -e "\e[32;1m[+] \e[0mSuccessfully Installed\e[0m"
    fi
    echo -e "\e[34m[*] \e[0mUse \e[32mapt\e[0m to install new packages\e[0m"
fi

# Install fish shell if not already installed
if ! command -v fish >/dev/null 2>&1; then
    echo -e "\e[34;1m[*] \e[0mInstalling fish shell\e[0m"
    safe_apt_get update -qq || true
    safe_apt_get install -y -qq fish || true
    if command -v fish >/dev/null 2>&1; then
        echo -e "\e[32;1m[+] \e[0mFish shell installed\e[0m"
    fi
fi

# Install cron if not already installed
if ! command -v cron >/dev/null 2>&1; then
    safe_apt_get install -y -qq cron || true
fi

# Create aterm-setup-storage command
if [ ! -f "$PREFIX/local/bin/aterm-setup-storage" ]; then
    mkdir -p "$PREFIX/local/bin" 2>/dev/null || true
    cat > "$PREFIX/local/bin/aterm-setup-storage" << 'STORAGEEOF'
#!/bin/sh
# Setup /sdcard symlink for Android storage access
# Works with Android 15+ scoped storage
# Requests storage permissions if needed

# Get package name from environment
PKG_NAME="${PKG:-${RISH_APPLICATION_ID}}"
if [ -z "$PKG_NAME" ]; then
    # Try to get from /proc/self/cmdline or other methods
    PKG_NAME=$(cat /proc/self/cmdline 2>/dev/null | cut -d: -f1 | tr -d '\0' || echo "")
fi

# Function to check if storage is accessible
check_storage_access() {
    for path in "/storage/emulated/0" "/sdcard" "/storage/sdcard0" "/mnt/sdcard"; do
        if [ -d "$path" ] && [ -r "$path" ] && [ -x "$path" ]; then
            # Test if we can actually list files
            if ls "$path" >/dev/null 2>&1; then
                echo "$path"
                return 0
            fi
        fi
    done
    return 1
}

# Function to request storage permissions
request_permissions() {
    echo "Requesting storage permissions..."
    
    if [ -n "$PKG_NAME" ] && command -v am >/dev/null 2>&1; then
        # Open app's permission settings page
        am start -a android.settings.APPLICATION_DETAILS_SETTINGS \
            -d "package:$PKG_NAME" >/dev/null 2>&1 || true
        
        echo "Please grant storage permissions in the settings that just opened."
        echo "Then run 'aterm-setup-storage' again."
        return 1
    else
        echo "Please grant storage permissions manually in Android Settings:"
        if [ -n "$PKG_NAME" ]; then
            echo "  Settings > Apps > $PKG_NAME > Permissions > Files and media"
        else
            echo "  Settings > Apps > aTerm > Permissions > Files and media"
        fi
        return 1
    fi
}

# Find Android external storage
ANDROID_STORAGE=""
ANDROID_STORAGE=$(check_storage_access)

# Also try to get from environment
if [ -z "$ANDROID_STORAGE" ] && [ -n "$EXTERNAL_STORAGE" ]; then
    if [ -d "$EXTERNAL_STORAGE" ] && [ -r "$EXTERNAL_STORAGE" ] && [ -x "$EXTERNAL_STORAGE" ]; then
        if ls "$EXTERNAL_STORAGE" >/dev/null 2>&1; then
            ANDROID_STORAGE="$EXTERNAL_STORAGE"
        fi
    fi
fi

# If no accessible storage found, request permissions
if [ -z "$ANDROID_STORAGE" ]; then
    echo "✗ Storage access not available"
    request_permissions
    exit 1
fi

# Create /sdcard if it doesn't exist or is not accessible
if [ ! -e "/sdcard" ] || [ ! -r "/sdcard" ] || [ ! -x "/sdcard" ]; then
    # Remove existing /sdcard if it's not working
    if [ -e "/sdcard" ] && ! ls "/sdcard" >/dev/null 2>&1; then
        rm -rf /sdcard 2>/dev/null || true
    fi
    
    # Try to create symlink
    if ln -sf "$ANDROID_STORAGE" /sdcard 2>/dev/null; then
        # Verify symlink works
        if [ -L "/sdcard" ] && ls "/sdcard" >/dev/null 2>&1; then
            echo "✓ Storage setup complete (symlink)"
            echo "  /sdcard -> $ANDROID_STORAGE"
            echo "  You can now access your Android storage at /sdcard"
            exit 0
        fi
    fi
    
    # If symlink fails, try bind mount (requires root or proot)
    mkdir -p /sdcard 2>/dev/null || true
    if mount --bind "$ANDROID_STORAGE" /sdcard 2>/dev/null; then
        # Verify mount works
        if [ -d "/sdcard" ] && ls "/sdcard" >/dev/null 2>&1; then
            echo "✓ Storage setup complete (bind mount)"
            echo "  /sdcard -> $ANDROID_STORAGE"
            echo "  You can now access your Android storage at /sdcard"
            exit 0
        fi
    fi
    
    echo "✗ Could not create /sdcard symlink or mount"
    echo "  Storage is available at: $ANDROID_STORAGE"
    echo "  You can access it directly at that path"
    exit 1
fi

# Verify /sdcard is working
if ls "/sdcard" >/dev/null 2>&1; then
    echo "✓ Storage already set up"
    if [ -L "/sdcard" ]; then
        TARGET=$(readlink -f /sdcard 2>/dev/null || readlink /sdcard 2>/dev/null || echo "unknown")
        echo "  /sdcard -> $TARGET"
    else
        echo "  /sdcard is accessible"
    fi
    echo "  You can access your Android storage at /sdcard"
    exit 0
else
    echo "✗ /sdcard exists but is not accessible"
    echo "  Removing and recreating..."
    rm -rf /sdcard 2>/dev/null || true
    # Retry setup
    exec "$0"
fi
STORAGEEOF
    chmod +x "$PREFIX/local/bin/aterm-setup-storage" 2>/dev/null || true
fi

# Copy fish color update script
if [ -f "$PREFIX/local/bin/update-fish-colors.sh" ]; then
    # Script already exists, just make it executable
    chmod +x "$PREFIX/local/bin/update-fish-colors.sh" 2>/dev/null || true
else
    # Create the script
    mkdir -p "$PREFIX/local/bin" 2>/dev/null || true
    cat > "$PREFIX/local/bin/update-fish-colors.sh" << 'SCRIPTEOF'
#!/bin/sh
# Script to update fish shell colors based on app theme
# This script reads the Android app's SharedPreferences to detect theme

# Paths to check for SharedPreferences
PREF_PATHS="/data/data/com.qali.aterm/shared_prefs/Settings.xml /data/data/com.qali.aterm.debug/shared_prefs/Settings.xml"

# Default to dark theme if we can't detect
IS_DARK_MODE=1

# Try to read the theme setting from SharedPreferences
for PREF_PATH in $PREF_PATHS; do
    if [ -f "$PREF_PATH" ]; then
        # Read default_night_mode value (MODE_NIGHT_YES=2, MODE_NIGHT_NO=1, MODE_NIGHT_FOLLOW_SYSTEM=0)
        NIGHT_MODE=$(grep -o 'name="default_night_mode"[^>]*>\([0-9]*\)</int>' "$PREF_PATH" 2>/dev/null | grep -o '[0-9]*' | tail -1)
        if [ -n "$NIGHT_MODE" ]; then
            # MODE_NIGHT_YES = 2 (dark), MODE_NIGHT_NO = 1 (light), MODE_NIGHT_FOLLOW_SYSTEM = 0
            if [ "$NIGHT_MODE" = "2" ]; then
                IS_DARK_MODE=1
            elif [ "$NIGHT_MODE" = "1" ]; then
                IS_DARK_MODE=0
            else
                # MODE_NIGHT_FOLLOW_SYSTEM - try to detect system theme
                IS_DARK_MODE=1  # Default to dark
            fi
            break
        fi
    fi
done

# Create fish config directory if it doesn't exist
mkdir -p ~/.config/fish 2>/dev/null || true

# Update fish colors based on theme
if [ "$IS_DARK_MODE" = "1" ]; then
    # Dark theme: Use light colors
    cat > ~/.config/fish/config.fish << 'FISHDARK'
# Fish shell configuration for dark theme (light colors)
set -g fish_color_normal white
set -g fish_color_command cyan
set -g fish_color_quote yellow
set -g fish_color_redirection magenta
set -g fish_color_end green
set -g fish_color_error red
set -g fish_color_param white
set -g fish_color_comment brblack
set -g fish_color_match --background=brblue
set -g fish_color_selection white --bold --background=brblack
set -g fish_color_search_match bryellow --background=brblack
set -g fish_color_history_current --bold
set -g fish_color_operator brcyan
set -g fish_color_escape brcyan
set -g fish_color_cwd green
set -g fish_color_cwd_root red
set -g fish_color_valid_path --underline
set -g fish_color_autosuggestion brblack
set -g fish_color_user brgreen
set -g fish_color_host normal
set -g fish_color_cancel -r
set -g fish_pager_color_completion normal
set -g fish_pager_color_description B3a06a yellow
set -g fish_pager_color_prefix white --bold --underline
set -g fish_pager_color_progress brwhite --background=cyan
FISHDARK
else
    # Light theme: Use dark colors
    cat > ~/.config/fish/config.fish << 'FISHLIGHT'
# Fish shell configuration for light theme (dark colors)
set -g fish_color_normal black
set -g fish_color_command blue
set -g fish_color_quote yellow
set -g fish_color_redirection magenta
set -g fish_color_end green
set -g fish_color_error red
set -g fish_color_param black
set -g fish_color_comment brblack
set -g fish_color_match --background=brblue
set -g fish_color_selection black --bold --background=brwhite
set -g fish_color_search_match bryellow --background=brwhite
set -g fish_color_history_current --bold
set -g fish_color_operator brcyan
set -g fish_color_escape brcyan
set -g fish_color_cwd green
set -g fish_color_cwd_root red
set -g fish_color_valid_path --underline
set -g fish_color_autosuggestion brblack
set -g fish_color_user brgreen
set -g fish_color_host normal
set -g fish_color_cancel -r
set -g fish_pager_color_completion normal
set -g fish_pager_color_description B3a06a yellow
set -g fish_pager_color_prefix black --bold --underline
set -g fish_pager_color_progress brblack --background=cyan
FISHLIGHT
fi

# Make script executable
chmod +x ~/.config/fish/config.fish 2>/dev/null || true
SCRIPTEOF
    chmod +x "$PREFIX/local/bin/update-fish-colors.sh" 2>/dev/null || true
fi

# Run the script once to set initial colors immediately
"$PREFIX/local/bin/update-fish-colors.sh" 2>/dev/null || true

# Setup storage access (creates /sdcard symlink)
"$PREFIX/local/bin/aterm-setup-storage" 2>/dev/null || true

# Add to crontab to run every minute (checks for theme changes in new tabs)
(crontab -l 2>/dev/null | grep -v "update-fish-colors.sh"; echo "* * * * * $PREFIX/local/bin/update-fish-colors.sh >/dev/null 2>&1") | crontab - 2>/dev/null || true

# Start cron daemon if not running
if ! pgrep -x cron >/dev/null 2>&1; then
    # Try multiple methods to start cron
    if command -v service >/dev/null 2>&1; then
        service cron start 2>/dev/null || true
    elif [ -f /etc/init.d/cron ]; then
        /etc/init.d/cron start 2>/dev/null || true
    elif [ -f /usr/sbin/cron ]; then
        /usr/sbin/cron 2>/dev/null || true
    fi
    # Wait a moment to ensure it started
    sleep 1
    # Verify it's running
    if pgrep -x cron >/dev/null 2>&1; then
        echo -e "\e[32;1m[+] \e[0mCron daemon started\e[0m"
    else
        echo -e "\e[33;1m[!] \e[0mWarning: Failed to start cron daemon (this is normal in some environments)\e[0m"
    fi
fi

#fix linker warning
if [[ ! -f /linkerconfig/ld.config.txt ]];then
    mkdir -p /linkerconfig
    touch /linkerconfig/ld.config.txt
fi

# Fix group warnings by adding missing group entries
if [ -f /etc/group ]; then
    for gid in 3003 9997 20609 20610 50609 50610 99909997; do
        if ! grep -q "^[^:]*:[^:]*:$gid:" /etc/group 2>/dev/null; then
            echo "android_$gid:x:$gid:" >> /etc/group 2>/dev/null || true
        fi
    done
fi

if [ "$#" -eq 0 ]; then
    source /etc/profile 2>/dev/null || true
    export PS1="\[\e[38;5;46m\]\u\[\033[39m\]@reterm \[\033[39m\]\w \[\033[0m\]\\$ "
    cd $HOME
    # Start fish shell if available, otherwise fall back to bash
    if command -v fish >/dev/null 2>&1; then
        # Ensure fish colors are set before starting
        "$PREFIX/local/bin/update-fish-colors.sh" 2>/dev/null || true
        exec fish
    else
        /bin/bash
    fi
else
    exec "$@"
fi

