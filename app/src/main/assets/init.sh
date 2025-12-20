set -e  # Exit immediately on Failure

export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/share/bin:/usr/share/sbin:/usr/local/bin:/usr/local/sbin:/system/bin:/system/xbin
export HOME=/root

if [ ! -s /etc/resolv.conf ]; then
    echo "nameserver 8.8.8.8" > /etc/resolv.conf
fi


export PS1="\[\e[38;5;46m\]\u\[\033[39m\]@reterm \[\033[39m\]\w \[\033[0m\]\\$ "
# shellcheck disable=SC2034
export PIP_BREAK_SYSTEM_PACKAGES=1
required_packages="bash gcompat glib nano"
missing_packages=""
for pkg in $required_packages; do
    if ! apk info -e $pkg >/dev/null 2>&1; then
        missing_packages="$missing_packages $pkg"
    fi
done
if [ -n "$missing_packages" ]; then
    echo -e "\e[34;1m[*] \e[0mInstalling Important packages\e[0m"
    apk update && apk upgrade
    apk add $missing_packages
    if [ $? -eq 0 ]; then
        echo -e "\e[32;1m[+] \e[0mSuccessfully Installed\e[0m"
    fi
    echo -e "\e[34m[*] \e[0mUse \e[32mapk\e[0m to install new packages\e[0m"
fi

# Install fish shell if not already installed
if ! command -v fish >/dev/null 2>&1; then
    echo -e "\e[34;1m[*] \e[0mInstalling fish shell\e[0m"
    apk add fish 2>/dev/null || true
    if command -v fish >/dev/null 2>&1; then
        echo -e "\e[32;1m[+] \e[0mFish shell installed\e[0m"
    fi
fi

# Install cron if not already installed
if ! command -v crond >/dev/null 2>&1; then
    apk add dcron 2>/dev/null || true
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
if ! pgrep -x crond >/dev/null 2>&1; then
    # Try to start crond with proper flags
    # -b: run in background
    # -S: send output to syslog (if available)
    # -l 0: log level 0 (minimal logging)
    crond -b -S -l 0 >/dev/null 2>&1 &
    sleep 1
    if pgrep -x crond >/dev/null 2>&1; then
        echo -e "\e[32;1m[+] \e[0mCron daemon started\e[0m"
    else
        # Try alternative method: run in foreground in background using nohup
        nohup crond -f -l 0 >/dev/null 2>&1 &
        sleep 1
        if pgrep -x crond >/dev/null 2>&1; then
            echo -e "\e[32;1m[+] \e[0mCron daemon started (alternative method)\e[0m"
        else
            # Final fallback: try simple background start without flags
            crond >/dev/null 2>&1 &
            sleep 1
            if pgrep -x crond >/dev/null 2>&1; then
                echo -e "\e[32;1m[+] \e[0mCron daemon started (fallback method)\e[0m"
            else
                echo -e "\e[33;1m[!] \e[0mWarning: Failed to start cron daemon (fish theme updates may not work automatically)\e[0m"
                echo -e "\e[33;1m[!] \e[0mYou can manually run: $PREFIX/local/bin/update-fish-colors.sh\e[0m"
            fi
        fi
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
    source /etc/profile
    export PS1="\[\e[38;5;46m\]\u\[\033[39m\]@reterm \[\033[39m\]\w \[\033[0m\]\\$ "
    cd $HOME
    # Start fish shell if available, otherwise fall back to ash
    if command -v fish >/dev/null 2>&1; then
        # Ensure fish colors are set before starting
        "$PREFIX/local/bin/update-fish-colors.sh" 2>/dev/null || true
        exec fish
    else
        /bin/ash
    fi
else
    exec "$@"
fi