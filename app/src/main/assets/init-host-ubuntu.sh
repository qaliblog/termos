# Determine rootfs file and directory from environment or defaults
ROOTFS_FILE="${ROOTFS_FILE:-ubuntu.tar.gz}"
ROOTFS_DIR="${ROOTFS_DIR:-ubuntu}"

# If ROOTFS_DIR is not set, infer from ROOTFS_FILE
if [ -z "$ROOTFS_DIR" ] || [ "$ROOTFS_DIR" = "ubuntu" ]; then
    if [ "$ROOTFS_FILE" = "alpine.tar.gz" ]; then
        ROOTFS_DIR="alpine"
    elif [ "$ROOTFS_FILE" != "ubuntu.tar.gz" ]; then
        # For custom rootfs, use filename without extension
        ROOTFS_DIR=$(echo "$ROOTFS_FILE" | sed 's/\.tar\.gz$//' | sed 's/\.tar$//' | tr '[:upper:]' '[:lower:]' | tr ' ' '_')
    fi
fi

ROOTFS_DIR_PATH="$PREFIX/local/$ROOTFS_DIR"

mkdir -p "$ROOTFS_DIR_PATH"

# Extract rootfs if directory is empty (excluding root and tmp)
if [ -z "$(ls -A "$ROOTFS_DIR_PATH" 2>/dev/null | grep -vE '^(root|tmp)$')" ]; then
    ROOTFS_FILE_PATH="$PREFIX/files/$ROOTFS_FILE"
    if [ ! -f "$ROOTFS_FILE_PATH" ]; then
        echo "Error: $ROOTFS_FILE not found at $ROOTFS_FILE_PATH"
        exit 1
    fi
    echo "Extracting $ROOTFS_FILE to $ROOTFS_DIR..."
    # Use appropriate tar flags based on file extension
    # Suppress symlink warnings (normal on Android - symlinks point to system binaries)
    if echo "$ROOTFS_FILE" | grep -q "\.tar\.gz$"; then
        tar -xzf "$ROOTFS_FILE_PATH" -C "$ROOTFS_DIR_PATH" --no-same-owner --no-same-permissions 2>&1 | \
            grep -v "tar: Removing leading" | \
            grep -v "can't link" | \
            grep -v "not under" | \
            grep -v "tar: had errors" || true
    else
        tar -xf "$ROOTFS_FILE_PATH" -C "$ROOTFS_DIR_PATH" --no-same-owner --no-same-permissions 2>&1 | \
            grep -v "tar: Removing leading" | \
            grep -v "can't link" | \
            grep -v "not under" | \
            grep -v "tar: had errors" || true
    fi
    
    # Verify extraction was successful
    if [ ! -d "$ROOTFS_DIR_PATH/usr" ] && [ ! -d "$ROOTFS_DIR_PATH/bin" ] && [ ! -d "$ROOTFS_DIR_PATH/etc" ]; then
        echo "Error: Failed to extract $ROOTFS_FILE - no system directories found"
        exit 1
    fi
    echo "$ROOTFS_FILE extracted successfully (some symlink warnings are normal)"
fi

if [ ! -e "$PREFIX/local/bin/proot" ]; then
    cp "$PREFIX/files/proot" "$PREFIX/local/bin"
    chmod 755 "$PREFIX/local/bin/proot" 2>/dev/null || true
fi

for sofile in "$PREFIX/files/"*.so.2; do
    dest="$PREFIX/local/lib/$(basename "$sofile")"
    [ ! -e "$dest" ] && cp "$sofile" "$dest"
done


ARGS="--kill-on-exit"
ARGS="$ARGS -w /"

for system_mnt in /apex /odm /product /system /system_ext /vendor \
 /linkerconfig/ld.config.txt \
 /linkerconfig/com.android.art/ld.config.txt \
 /plat_property_contexts /property_contexts; do

 if [ -e "$system_mnt" ]; then
  system_mnt=$(realpath "$system_mnt")
  ARGS="$ARGS -b ${system_mnt}"
 fi
done
unset system_mnt

ARGS="$ARGS -b /sdcard"
ARGS="$ARGS -b /storage"
ARGS="$ARGS -b /dev"
ARGS="$ARGS -b /data"
ARGS="$ARGS -b /dev/urandom:/dev/random"
ARGS="$ARGS -b /proc"
ARGS="$ARGS -b $PREFIX"
ARGS="$ARGS -b $PREFIX/local/stat:/proc/stat"
ARGS="$ARGS -b $PREFIX/local/vmstat:/proc/vmstat"

if [ -e "/proc/self/fd" ]; then
  ARGS="$ARGS -b /proc/self/fd:/dev/fd"
fi

if [ -e "/proc/self/fd/0" ]; then
  ARGS="$ARGS -b /proc/self/fd/0:/dev/stdin"
fi

if [ -e "/proc/self/fd/1" ]; then
  ARGS="$ARGS -b /proc/self/fd/1:/dev/stdout"
fi

if [ -e "/proc/self/fd/2" ]; then
  ARGS="$ARGS -b /proc/self/fd/2:/dev/stderr"
fi


ARGS="$ARGS -b $PREFIX"
ARGS="$ARGS -b /sys"

if [ ! -d "$ROOTFS_DIR_PATH/tmp" ]; then
 mkdir -p "$ROOTFS_DIR_PATH/tmp"
 chmod 1777 "$ROOTFS_DIR_PATH/tmp"
fi
ARGS="$ARGS -b $ROOTFS_DIR_PATH/tmp:/dev/shm"

ARGS="$ARGS -r $ROOTFS_DIR_PATH"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

# Ensure PROOT_TMP_DIR exists if set (create parent directory too)
if [ -n "$PROOT_TMP_DIR" ]; then
    mkdir -p "$(dirname "$PROOT_TMP_DIR")" 2>/dev/null || true
    mkdir -p "$PROOT_TMP_DIR"
    chmod 700 "$PROOT_TMP_DIR" 2>/dev/null || true
fi

# Use absolute path to shell inside rootfs to avoid using system shell
# Use -e instead of -f to check for existence (works for files, symlinks, etc.)
SHELL_PATH="/bin/sh"
USE_BUSYBOX_SH=false

# Check if /bin/sh exists (even as symlink - proot will resolve it)
if [ -e "$ROOTFS_DIR_PATH$SHELL_PATH" ]; then
    # Shell found, use it directly (proot handles symlink resolution)
    : # No-op, SHELL_PATH is already set
elif [ -e "$ROOTFS_DIR_PATH/bin/bash" ]; then
    SHELL_PATH="/bin/bash"
elif [ -e "$ROOTFS_DIR_PATH/usr/bin/sh" ]; then
    SHELL_PATH="/usr/bin/sh"
elif [ -e "$ROOTFS_DIR_PATH/bin/ash" ]; then
    # Alpine Linux uses ash
    SHELL_PATH="/bin/ash"
elif [ -e "$ROOTFS_DIR_PATH/bin/busybox" ]; then
    # Alpine uses busybox as shell - need to call it with 'sh' argument
    SHELL_PATH="/bin/busybox"
    USE_BUSYBOX_SH=true
else
    # Debug: list what's actually in bin directory
    echo "Debug: Checking rootfs structure at $ROOTFS_DIR_PATH"
    [ -d "$ROOTFS_DIR_PATH/bin" ] && echo "bin contents: $(ls -la "$ROOTFS_DIR_PATH/bin" 2>/dev/null | head -10)" || echo "bin directory not found"
    [ -d "$ROOTFS_DIR_PATH/usr/bin" ] && echo "usr/bin contents: $(ls -la "$ROOTFS_DIR_PATH/usr/bin" 2>/dev/null | head -10)" || echo "usr/bin directory not found"
    echo "Error: Rootfs shell not found in $ROOTFS_DIR_PATH. Cannot proceed."
    exit 1
fi

# Verify the shell is actually accessible
if [ ! -e "$ROOTFS_DIR_PATH$SHELL_PATH" ]; then
    echo "Error: Shell at $SHELL_PATH not found in rootfs at $ROOTFS_DIR_PATH"
    exit 1
fi

# Verify proot binary exists and is executable
if [ ! -f "$PREFIX/local/bin/proot" ] || [ ! -x "$PREFIX/local/bin/proot" ]; then
    echo "Error: proot binary not found or not executable at $PREFIX/local/bin/proot"
    exit 1
fi

# If using busybox as shell, we need to pass 'sh' as argument
if [ "$USE_BUSYBOX_SH" = "true" ]; then
    $LINKER $PREFIX/local/bin/proot $ARGS $SHELL_PATH sh $PREFIX/local/bin/init "$@"
else
    $LINKER $PREFIX/local/bin/proot $ARGS $SHELL_PATH $PREFIX/local/bin/init "$@"
fi

