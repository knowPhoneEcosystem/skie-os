#!/bin/bash

# ============================================
# SkieOS Provisioning Script
# Version: 0.1 — Phase 2 complete
# Run: bash provision.sh
# ============================================

set -e

PACKAGE="com.skiephone.app"
LAUNCHER=".launcher.LauncherActivity"
ACCESSIBILITY=".services.SkieAccessibilityService"
ACCESSIBILITY_ID="$PACKAGE/$PACKAGE$ACCESSIBILITY"

echo ""
echo "  skie provisioning"
echo "  ─────────────────"
echo ""

# ── Step 1: Check ADB device connected ──────────────────────────────
echo "► Checking device connection..."
DEVICE=$(adb devices | grep -v "List" | grep "device" | awk '{print $1}')
if [ -z "$DEVICE" ]; then
  echo "✗ No device found. Connect via USB and enable USB debugging."
  exit 1
fi
echo "✓ Device connected: $DEVICE"

# ── Step 2: Check app is installed ──────────────────────────────────
echo "► Checking SkieOS is installed..."
INSTALLED=$(adb shell pm list packages | grep "$PACKAGE" || true)
if [ -z "$INSTALLED" ]; then
  echo "✗ SkieOS not installed. Build and install the APK first."
  exit 1
fi
echo "✓ SkieOS installed"

# ── Step 3 & 4: Permissions granted by app at runtime ───────────────
echo "► Launching app to request permissions..."
adb shell am start -n $PACKAGE/.MainActivity
sleep 3
echo "✓ App launched — permissions requested via runtime dialog"

# ── Step 5: Set SkieOS as default home launcher ──────────────────────
echo "► Setting SkieOS as default launcher..."
adb shell cmd package set-home-activity $PACKAGE/$PACKAGE$LAUNCHER
echo "✓ Default launcher set"

# ── Step 6: Enable accessibility service ────────────────────────────
echo "► Enabling accessibility service..."
adb shell settings put secure enabled_accessibility_services "$ACCESSIBILITY_ID"
adb shell settings put secure accessibility_enabled 1
echo "✓ Accessibility service enabled"

# ── Step 7: Disable status bar interactions (best effort) ───────────
echo "► Restricting status bar..."
adb shell settings put global policy_control "immersive.status=*" 2>/dev/null || true
echo "✓ Status bar restricted"

# ── Step 8: Keep screen on while provisioning ───────────────────────
echo "► Setting screen timeout to max..."
adb shell settings put system screen_off_timeout 1800000
echo "✓ Screen timeout set to 30 mins"

# ────────────────────────────────────────────────────────────────────
# TODO — Phase 3 Step 23: Device Owner registration
# adb shell dpm set-device-owner $PACKAGE/.admin.SkieDeviceAdminReceiver
# Requires factory reset + provisioning via QR or NFC
# ────────────────────────────────────────────────────────────────────

# TODO — Phase 5: Additional hardening once Device Owner is set
# - Disable USB debugging remotely
# - Block factory reset
# - Disable unknown app installs
# - Lock down settings sections
# ────────────────────────────────────────────────────────────────────

echo ""
echo "  ✓ SkieOS provisioning complete"
echo "  Device is ready."
echo ""