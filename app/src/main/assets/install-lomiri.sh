#!/bin/sh
# Ensures Lomiri desktop is installed for Termos OS

set -e

echo "[Termos] Installing Lomiri desktop environment"

apt update

DEBIAN_FRONTEND=noninteractive apt install -y \
    lomiri \
    lomiri-session \
    lomiri-ui-toolkit \
    ubuntu-touch-sounds \
    ubuntu-touch-session \
    dbus-x11 \
    x11-xserver-utils \
    tigervnc-standalone-server

echo "[Termos] Lomiri installation complete"
