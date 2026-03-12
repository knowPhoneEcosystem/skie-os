package com.skiephone.app.policy

object SkieConfig {

    // System packages that must never be blocked
    val systemPackages = listOf(
        "com.skiephone.app",
        "com.android.systemui",
        "com.android.launcher3",
        "com.google.android.googlequicksearchbox",
        "com.samsung.android.app.launcher",
        "com.miui.home",
        "com.oneplus.launcher",
        "com.oppo.launcher",
        "com.vivo.launcher",
        "com.realme.launcher",
        "com.android.settings"
    )

    // Phone/Dialer apps by vendor
    val dialerPackages = listOf(
        "com.google.android.dialer",
        "com.android.dialer",
        "com.samsung.android.dialer",
        "com.miui.phone",
        "com.oneplus.contacts",
        "com.oppo.contacts",
        "com.vivo.contacts",
        "com.realme.contacts",
        "com.motorola.phone"
    )

    // Messaging/SMS apps by vendor
    val messagingPackages = listOf(
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.samsung.android.messaging",
        "com.miui.messaging",
        "com.oneplus.mms",
        "com.oppo.message",
        "com.vivo.message",
        "com.realme.message",
        "com.motorola.messaging"
    )

    val essentialApps = listOf(
        "com.google.android.apps.maps",
        "com.google.android.calculator",
        "com.samsung.android.calculator",
        "com.miui.calculator",
        "com.oneplus.calculator",
        "com.google.android.deskclock",
        "com.samsung.android.clock",
        "com.android.deskclock",
        "com.oneplus.deskclock",
        "com.google.android.keep",
        "com.google.android.apps.weather",
        "net.oneplus.weather",
        "com.coloros.weather.service"
    )

    val schoolApps = listOf(
        "com.google.android.apps.classroom",
        "com.google.android.apps.tachyon",
        "com.google.android.docs",
        "com.google.android.apps.docs",
        "com.google.android.calendar"
    )

    val cameraApps = listOf(
        "com.google.android.GoogleCamera",
        "com.samsung.android.camera",
        "com.miui.camera",
        "com.oneplus.camera",
        "com.oplus.camera",
        "com.oppo.camera",
        "com.vivo.camera",
        "com.realme.camera",
        "com.motorola.camera2",
        "com.android.camera2"
    )

    // Full whitelist for accessibility service blocking
    val whitelistedPackages: List<String> =
        systemPackages + dialerPackages + messagingPackages + essentialApps + schoolApps + cameraApps
}