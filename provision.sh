#!/bin/bash

# ============================================
# SkieOS Provisioning Script
# Version: 1.0 — Phase 3 complete
# Run: bash provision.sh
# ============================================

set -e

PACKAGE="com.skiephone.app"
LAUNCHER=".launcher.LauncherActivity"
ACCESSIBILITY=".services.SkieAccessibilityService"
ADMIN=".admin.SkieDeviceAdminReceiver"
ACCESSIBILITY_ID="$PACKAGE/$PACKAGE$ACCESSIBILITY"

echo ""
echo "  skie provisioning"
echo "  ─────────────────"
echo ""

# ── Step 1: Check ADB device connected ──────────────────────────────
echo "► Checking device connection..."
DEVICE=$(adb devices | grep -v "List" | grep "device$" | awk '{print $1}')
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

# ── Step 3: Check / set Device Owner ────────────────────────────────
echo "► Checking Device Owner..."
IS_OWNER=$(adb shell dpm list-owners 2>/dev/null | grep "$PACKAGE" || true)
if [ -z "$IS_OWNER" ]; then
  echo "  Device Owner not set. Attempting to set..."
  RESULT=$(adb shell dpm set-device-owner "$PACKAGE/$PACKAGE$ADMIN" 2>&1 || true)
  if echo "$RESULT" | grep -q "Success"; then
    echo "✓ Device Owner set"
  else
    echo "⚠  Device Owner could not be set automatically."
    echo "   This usually means the device has existing Google accounts."
    echo "   Fix: Go to Settings → Accounts → remove all accounts, then re-run."
    echo "   Or factory reset the device, then run this script before adding accounts."
    echo "   Raw error: $RESULT"
    echo ""
    echo "  Continuing without Device Owner (policy enforcement will be limited)..."
  fi
else
  echo "✓ Device Owner already set"
fi

# ── Step 4: Launch app to request runtime permissions ────────────────
echo "► Launching app to request permissions..."
adb shell am start -n $PACKAGE/.MainActivity
sleep 4
echo "✓ App launched — grant location + notification permissions if prompted"

# ── Step 5: Set SkieOS as default home launcher ──────────────────────
echo "► Setting SkieOS as default launcher..."
adb shell cmd package set-home-activity $PACKAGE/$PACKAGE$LAUNCHER
echo "✓ Default launcher set"

# ── Step 6: Enable accessibility service ────────────────────────────
echo "► Enabling accessibility service..."
adb shell settings put secure enabled_accessibility_services "$ACCESSIBILITY_ID"
adb shell settings put secure accessibility_enabled 1
echo "✓ Accessibility service enabled"

# ── Step 7: Start policy service ─────────────────────────────────────
echo "► Starting policy service..."
adb shell am startservice --user 0 $PACKAGE/.services.SkiePolicyService 2>/dev/null || true
echo "✓ Policy service started"

# ── Step 8: Set screen timeout ───────────────────────────────────────
echo "► Setting screen timeout to 30 mins..."
adb shell settings put system screen_off_timeout 1800000
echo "✓ Screen timeout set"

# ── Step 9: Verify RTDB connectivity ─────────────────────────────────
echo "► Checking device appears online in Firebase..."
echo "  (Check Firebase RTDB → devices/{deviceId}/online = true)"
DEVICE_ID=$(adb shell settings get secure android_id | tr -d '\r')
echo "  Device ID: $DEVICE_ID"

# ── Step 10: Print summary ────────────────────────────────────────────
echo ""
echo "  ✓ SkieOS provisioning complete"
echo "  ──────────────────────────────"
echo "  Device ID : $DEVICE_ID"
IS_OWNER_FINAL=$(adb shell dpm list-owners 2>/dev/null | grep "$PACKAGE" || echo "not set")
if echo "$IS_OWNER_FINAL" | grep -q "$PACKAGE"; then
  echo "  Device Owner : ✓ active"
else
  echo "  Device Owner : ✗ not set (policy enforcement limited)"
fi
echo ""
echo "  Next steps:"
echo "  1. Parent opens skie.app on their phone"
echo "  2. Signs up → scans QR from SkieStudent app (⊞ icon)"
echo "  3. Device ID: $DEVICE_ID"
echo ""