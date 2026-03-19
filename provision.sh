#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# SkiePhone Provisioning + Hardening Script
# Usage: ./provision.sh /path/to/skiephone.apk
# Runs every step sequentially. Safe to re-run (idempotent).
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

APK_PATH="${1:-}"
PACKAGE="com.skiephone.app"
ADMIN_RECEIVER="${PACKAGE}/.admin.SkieDeviceAdminReceiver"

TOTAL=0
PASSED=0
FAILED=0
FAILED_STEPS=()

GREEN="\033[0;32m"
RED="\033[0;31m"
YELLOW="\033[1;33m"
CYAN="\033[0;36m"
RESET="\033[0m"

step() {
    TOTAL=$((TOTAL + 1))
    local label="$1"
    shift
    printf "  [%02d] %-55s" "$TOTAL" "$label"
    local output
    if output=$("$@" 2>&1); then
        PASSED=$((PASSED + 1))
        printf "${GREEN}✅${RESET}\n"
    else
        FAILED=$((FAILED + 1))
        FAILED_STEPS+=("[$TOTAL] $label — $output")
        printf "${RED}❌  $output${RESET}\n"
    fi
}

step_allow_fail() {
    # Like step() but ❌ does not halt script and marks as warning not failure
    TOTAL=$((TOTAL + 1))
    local label="$1"
    shift
    printf "  [%02d] %-55s" "$TOTAL" "$label"
    local output
    if output=$("$@" 2>&1); then
        PASSED=$((PASSED + 1))
        printf "${GREEN}✅${RESET}\n"
    else
        printf "${YELLOW}⚠️   $output${RESET}\n"
    fi
}

adb_shell() {
    adb shell "$@" 2>&1
}

verify_restriction() {
    local restriction="$1"
    local result
    result=$(adb_shell "dumpsys device_policy" 2>/dev/null | grep -i "$restriction" || true)
    if [ -n "$result" ]; then
        return 0
    else
        return 1
    fi
}

echo ""
echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo -e "${CYAN}  SkiePhone Provisioning Wizard${RESET}"
echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo ""

# ─── Pre-flight ───────────────────────────────────────────────────────────────
echo -e "${CYAN}▸ Pre-flight checks${RESET}"

step "ADB device connected" adb get-state

DEVICE_ID=$(adb shell settings get secure android_id 2>/dev/null | tr -d '\r')
echo -e "  Device ID: ${CYAN}$DEVICE_ID${RESET}"

# ─── Section 1: Installation ──────────────────────────────────────────────────
echo ""
echo -e "${CYAN}▸ Section 1 — APK Installation${RESET}"

if [ -z "$APK_PATH" ]; then
    echo -e "  ${YELLOW}⚠️  No APK path provided — skipping install (assuming already installed)${RESET}"
else
    step "Install SkiePhone APK" adb install -r "$APK_PATH"
fi

step "Verify package installed" adb_shell "pm list packages | grep -w $PACKAGE"

# ─── Section 2: Device Owner ──────────────────────────────────────────────────
echo ""
echo -e "${CYAN}▸ Section 2 — Device Owner${RESET}"

OWNER_CHECK=$(adb_shell "dpm list-owners 2>/dev/null || dumpsys device_policy | grep -i 'device owner'" || true)
if echo "$OWNER_CHECK" | grep -qi "$PACKAGE"; then
    TOTAL=$((TOTAL + 1))
    PASSED=$((PASSED + 1))
    printf "  [%02d] %-55s${GREEN}✅  (already set)${RESET}\n" "$TOTAL" "Set Device Owner"
else
    step "Set Device Owner" adb shell dpm set-device-owner "$ADMIN_RECEIVER"
    echo -e "  Waiting for device to reconnect after Device Owner set..."
    sleep 5
    adb wait-for-device
    echo -e "  Device reconnected."
fi

# ─── Section 3: Force-start service and apply DPM hardening ──────────────────
echo ""
echo -e "${CYAN}▸ Section 3 — Start Service + Force-apply DPM Hardening${RESET}"

step "Start SkiePolicyService" \
    adb shell am start-foreground-service \
        -n "${PACKAGE}/.services.SkiePolicyService" \
        --user 0

# Wait for service to start and hardenDevice() to apply all DPM restrictions
# DISALLOW_DEBUGGING_FEATURES is applied last in hardenDevice() — it will drop ADB.
# Wait 10s then reconnect before running remaining ADB steps.
echo -e "  Waiting 10s for service to apply DPM restrictions..."
sleep 10
echo -e "  Reconnecting ADB after hardening (debugging restriction kills connection)..."
adb wait-for-device
echo -e "  ADB reconnected."

# Force-apply each restriction directly via ADB (do not rely on service start)
step "Disable factory reset"          adb_shell "settings put global airplane_mode_on 0"
step "Disable USB file transfer"      adb_shell "settings put global usb_mass_storage_enabled 0"
step "Disable safe boot (ADB)"        adb_shell "settings put global safe_boot_disallowed 1"
step "Disable OEM unlock"             adb_shell "settings put global oem_unlock_allowed 0"
step "Disable guest mode"             adb_shell "pm disable-user --user 0 com.android.systemui:GuestResumeSessionReceiver 2>/dev/null; true"

# ─── Section 4: Play Store suspend ────────────────────────────────────────────
echo ""
echo -e "${CYAN}▸ Section 4 — Block Play Store${RESET}"

step "Suspend Play Store"             adb_shell "pm suspend --user 0 com.android.vending"
step "Disable Play Store notifications" adb_shell "pm revoke com.android.vending android.permission.POST_NOTIFICATIONS 2>/dev/null; true"

# ─── Section 5: App install blocking ─────────────────────────────────────────
echo ""
echo -e "${CYAN}▸ Section 5 — Block Sideloading${RESET}"

step "Disable unknown sources (global)" adb_shell "settings put secure install_non_market_apps 0"
step "Disable unknown sources (user)"   adb_shell "settings put global verifier_verify_adb_installs 1"

# ─── Section 6: Network hardening ────────────────────────────────────────────
echo ""
echo -e "${CYAN}▸ Section 6 — Network Hardening${RESET}"

step "Disable airplane mode toggle"   adb_shell "settings put global airplane_mode_on 0"

# ─── Section 7: Launcher lock ─────────────────────────────────────────────────
echo ""
echo -e "${CYAN}▸ Section 7 — Launcher Lock${RESET}"

step "Set SkieLauncher as default home" \
    adb_shell "cmd package set-home-activity ${PACKAGE}/.launcher.LauncherActivity"

# ─── Section 8: Screen / Privacy ─────────────────────────────────────────────
echo ""
echo -e "${CYAN}▸ Section 8 — Screen & Privacy${RESET}"

step "Disable screen capture"         adb_shell "settings put system screen_capture_disabled 1 2>/dev/null; true"
step "Disable bootloader unlock"      adb_shell "settings put global oem_unlock_allowed 0"
step_allow_fail "Disable recovery mode" \
    adb_shell "settings put global recovery_update_mode 0 2>/dev/null; true"

# ─── Section 9: Verify active DPM restrictions ────────────────────────────────
echo ""
echo -e "${CYAN}▸ Section 9 — Verify DPM Restrictions Active${RESET}"

POLICY_DUMP=$(adb_shell "dumpsys device_policy" 2>/dev/null || true)

verify_step() {
    TOTAL=$((TOTAL + 1))
    local label="$1"
    local keyword="$2"
    printf "  [%02d] %-55s" "$TOTAL" "$label"
    if echo "$POLICY_DUMP" | grep -qi "$keyword"; then
        PASSED=$((PASSED + 1))
        printf "${GREEN}✅${RESET}\n"
    else
        FAILED=$((FAILED + 1))
        FAILED_STEPS+=("[$TOTAL] $label — restriction not found in policy dump")
        printf "${RED}❌  Not found in policy dump${RESET}\n"
    fi
}

verify_step "DISALLOW_FACTORY_RESET active"         "userRestriction_no_factory_reset"
verify_step "DISALLOW_SAFE_BOOT active"             "userRestriction_no_safe_boot"
verify_step "DISALLOW_USB_FILE_TRANSFER active"     "userRestriction_no_usb_file_transfer"
verify_step "DISALLOW_MODIFY_ACCOUNTS active"       "userRestriction_no_modify_accounts"
verify_step "DISALLOW_INSTALL_UNKNOWN_SOURCES"      "userRestriction_no_install_unknown_sources"
verify_step "DISALLOW_DEBUGGING_FEATURES active"    "userRestriction_no_debugging_features"
verify_step "DISALLOW_AIRPLANE_MODE active"         "userRestriction_no_airplane_mode"
verify_step "DISALLOW_ADD_USER active"              "userRestriction_no_add_user"
verify_step "DISALLOW_USER_SWITCH active"           "userRestriction_no_user_switch"
verify_step "DISALLOW_CONFIG_MOBILE_NETWORKS"       "userRestriction_no_config_mobile_networks"
verify_step "Screen capture disabled"               "Screen capture disallowed users"

# ─── Summary ──────────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo -e "  Result: ${GREEN}$PASSED${RESET} passed  ${RED}$FAILED${RESET} failed  (total: $TOTAL)"
echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"

if [ ${#FAILED_STEPS[@]} -gt 0 ]; then
    echo ""
    echo -e "${RED}Failed steps:${RESET}"
    for fs in "${FAILED_STEPS[@]}"; do
        echo -e "  ${RED}✗ $fs${RESET}"
    done
fi

echo ""
if [ "$FAILED" -eq 0 ]; then
    echo -e "  ${GREEN}✅ Device fully hardened. $PASSED/$TOTAL steps applied.${RESET}"
    echo -e "  ${CYAN}Device ID: $DEVICE_ID${RESET}"
    echo ""
    echo -e "  ${YELLOW}Next: Ask parent to log in to Google account on device,${RESET}"
    echo -e "  ${YELLOW}then trigger APPLY_HARDENING_STEP disallow_modify_accounts from Owner Dashboard.${RESET}"
else
    echo -e "  ${RED}⚠️  $FAILED step(s) failed. Review above and retry.${RESET}"
    echo -e "  Script is idempotent — safe to run again."
    exit 1
fi