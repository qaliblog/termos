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
                # This is a simple heuristic - in practice, you might want to check system settings
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
