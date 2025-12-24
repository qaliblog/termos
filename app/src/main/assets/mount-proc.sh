#!/bin/sh
# Helper script to mount /proc if not already mounted
# This fixes the "Error: /proc must be mounted" issue

echo "Checking /proc mount status..."

# Check if /proc is already mounted
if mountpoint -q /proc 2>/dev/null; then
    echo "✓ /proc is already mounted"
    exit 0
fi

echo "✗ /proc is not mounted, attempting to mount..."

# Try to bind mount from host system first
if [ -d /proc ] && [ -r /proc/version ] 2>/dev/null; then
    echo "Attempting bind mount from host /proc..."
    if mount --bind /proc /proc 2>/dev/null; then
        echo "✓ Successfully bind-mounted /proc from host"
        exit 0
    fi
fi

# Try to mount as proc filesystem
echo "Attempting to mount /proc as proc filesystem..."
if mount -t proc proc /proc 2>/dev/null; then
    echo "✓ Successfully mounted /proc as proc filesystem"
    exit 0
fi

# If both methods fail, at least create the directory
echo "⚠ Could not mount /proc, creating directory structure..."
mkdir -p /proc 2>/dev/null || true
echo "⚠ /proc directory created but not mounted"
echo "  You may need to run this script with proper permissions"
echo "  or ensure PRoot is configured to bind mount /proc"
exit 1
