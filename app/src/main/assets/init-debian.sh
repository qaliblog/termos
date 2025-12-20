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

# Update package lists and upgrade system
if [ ! -f /var/lib/apt/lists/lock ]; then
    echo -e "\e[34;1m[*] \e[0mUpdating package lists\e[0m"
    apt-get update -qq || true
fi

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
    apt-get update -qq
    apt-get upgrade -y -qq || true
    apt-get install -y -qq $missing_packages
    if [ $? -eq 0 ]; then
        echo -e "\e[32;1m[+] \e[0mSuccessfully Installed\e[0m"
    fi
    echo -e "\e[34m[*] \e[0mUse \e[32mapt\e[0m to install new packages\e[0m"
fi

# Install fish shell if not already installed
if ! command -v fish >/dev/null 2>&1; then
    echo -e "\e[34;1m[*] \e[0mInstalling fish shell\e[0m"
    apt-get update -qq
    apt-get install -y -qq fish 2>/dev/null || true
    if command -v fish >/dev/null 2>&1; then
        echo -e "\e[32;1m[+] \e[0mFish shell installed\e[0m"
    fi
fi

# Install cron if not already installed
if ! command -v cron >/dev/null 2>&1; then
    apt-get install -y -qq cron 2>/dev/null || true
fi

# Create aterm-setup-storage command (same script for all distros)
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

# Copy fish color update script (same as Ubuntu)
if [ -f "$PREFIX/local/bin/update-fish-colors.sh" ]; then
    chmod +x "$PREFIX/local/bin/update-fish-colors.sh" 2>/dev/null || true
else
    mkdir -p "$PREFIX/local/bin" 2>/dev/null || true
    cat > "$PREFIX/local/bin/update-fish-colors.sh" << 'SCRIPTEOF'
#!/bin/sh
PREF_PATHS="/data/data/com.qali.aterm/shared_prefs/Settings.xml /data/data/com.qali.aterm.debug/shared_prefs/Settings.xml"
IS_DARK_MODE=1
for PREF_PATH in $PREF_PATHS; do
    if [ -f "$PREF_PATH" ]; then
        NIGHT_MODE=$(grep -o 'name="default_night_mode"[^>]*>\([0-9]*\)</int>' "$PREF_PATH" 2>/dev/null | grep -o '[0-9]*' | tail -1)
        if [ -n "$NIGHT_MODE" ]; then
            if [ "$NIGHT_MODE" = "2" ]; then IS_DARK_MODE=1
            elif [ "$NIGHT_MODE" = "1" ]; then IS_DARK_MODE=0
            else IS_DARK_MODE=1; fi
            break
        fi
    fi
done
mkdir -p ~/.config/fish 2>/dev/null || true
if [ "$IS_DARK_MODE" = "1" ]; then
    cat > ~/.config/fish/config.fish << 'FISHDARK'
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
    cat > ~/.config/fish/config.fish << 'FISHLIGHT'
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
chmod +x ~/.config/fish/config.fish 2>/dev/null || true
SCRIPTEOF
    chmod +x "$PREFIX/local/bin/update-fish-colors.sh" 2>/dev/null || true
fi

"$PREFIX/local/bin/update-fish-colors.sh" 2>/dev/null || true

# Setup storage access (creates /sdcard symlink)
"$PREFIX/local/bin/aterm-setup-storage" 2>/dev/null || true

(crontab -l 2>/dev/null | grep -v "update-fish-colors.sh"; echo "* * * * * $PREFIX/local/bin/update-fish-colors.sh >/dev/null 2>&1") | crontab - 2>/dev/null || true

if ! pgrep -x cron >/dev/null 2>&1; then
    if command -v service >/dev/null 2>&1; then
        service cron start 2>/dev/null || true
    elif [ -f /etc/init.d/cron ]; then
        /etc/init.d/cron start 2>/dev/null || true
    elif [ -f /usr/sbin/cron ]; then
        /usr/sbin/cron 2>/dev/null || true
    fi
    sleep 1
    if pgrep -x cron >/dev/null 2>&1; then
        echo -e "\e[32;1m[+] \e[0mCron daemon started\e[0m"
    else
        echo -e "\e[33;1m[!] \e[0mWarning: Failed to start cron daemon\e[0m"
    fi
fi

if [[ ! -f /linkerconfig/ld.config.txt ]];then
    mkdir -p /linkerconfig
    touch /linkerconfig/ld.config.txt
fi

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
