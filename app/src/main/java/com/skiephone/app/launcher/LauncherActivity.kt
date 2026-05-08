package com.skiephone.app.launcher

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.skiephone.app.policy.SkieFocusConstants
import com.skiephone.app.services.SkiePolicyService
import com.skiephone.app.ui.SkieBaseActivity
import com.skiephone.app.ui.SkieStudentActivity
import com.skiephone.app.ui.theme.SkiePhoneTheme
import android.provider.Settings
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import com.skiephone.app.admin.SkieDeviceAdminReceiver
import com.skiephone.app.services.SkieSOSManager
import com.skiephone.app.services.SkieTravelManager
import android.util.Log as ALog
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

data class EffectiveApp(
    val packageName: String = "",
    val appName: String = "",
    val category: String = "",
    val distractionExpiresAt: Long = 0L  // 0 = not a timed distraction app
)

class LauncherActivity : SkieBaseActivity() {

    private val deviceId by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Current focus state — kept at Activity level so onResume can read it
    private var currentFocusMode: String = "none"
    private var currentFocusApp: String? = null

    // When "none" broadcast arrives while launcher is in background,
    // stopLockTask() must be deferred until onResume (must be foreground).
    private var stopLockTaskPending = false

    // Tracks whether we already did ONE proxy-bounce recovery for the current focus session.
    // Prevents looping: some apps (Google Drive, etc.) use a proxy/splash Activity that
    // finishes itself, briefly returning focus to the launcher. We detect this in onResume
    // and re-launch once with a delay to let the proxy fully finish first.
    private var focusLaunchRecoveryDone = false

    // Compose state holders — updated from BroadcastReceiver, trigger recomposition
    private var focusModeState = mutableStateOf("none")
    private var focusAppState  = mutableStateOf<String?>(null)

    // Triggers launcher grid rebuild when a package installs or uninstalls
    // Compose observes this — incrementing forces recomposition
    private var packageChangeTrigger = mutableStateOf(0)

    // Package install/remove receiver — primary trigger for grid rebuild after install
    // Complements the lastInstallTs RTDB fallback listener
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val packageName = intent.data?.schemeSpecificPart ?: return
            val replacing   = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (replacing) return // update — grid doesn't change
            android.util.Log.d(TAG, "packageReceiver: ${intent.action} pkg=$packageName — rebuild grid in 2s")
            // 2 second delay — PackageManager needs time to register the new package
            mainHandler.postDelayed({
                packageChangeTrigger.value = packageChangeTrigger.value + 1
            }, 2000)
        }
    }

    private val focusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != SkieFocusConstants.ACTION_FOCUS_CHANGED) return
            val mode = intent.getStringExtra(SkieFocusConstants.EXTRA_FOCUS_MODE) ?: "none"
            val app  = intent.getStringExtra(SkieFocusConstants.EXTRA_FOCUS_APP)
            android.util.Log.d(TAG, "focusReceiver: mode=$mode app=$app")

            currentFocusMode = mode
            currentFocusApp  = app
            focusLaunchRecoveryDone = false  // reset recovery flag on every new focus event

            // Update Compose state — triggers recomposition (grid filter, subtitle)
            focusModeState.value = mode
            focusAppState.value  = app

            // setLockTaskPackages was already called by engine BEFORE this broadcast arrived
            if (mode != "none") {
                try {
                    startLockTask()
                    android.util.Log.d(TAG, "startLockTask OK — mode=$mode")
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "startLockTask failed: ${e.message}")
                }
                // Launch the focus app after a short delay to ensure lock task is fully entered
                if (!app.isNullOrEmpty()) {
                    mainHandler.postDelayed({ launchFocusApp(app) }, 300)
                }
            } else {
                // Focus ended — bring launcher to front, then stopLockTask in onResume
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    stopLockTaskPending = true
                    android.util.Log.d(TAG, "focus ended — bringing launcher to front, stopLockTask deferred to onResume")
                } else {
                    try {
                        stopLockTask()
                        repinLauncher()
                        android.util.Log.d(TAG, "stopLockTask OK (inline)")
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "stopLockTask failed: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(backCallback)
        ensureServiceRunning()
        // Register for full activity lifetime — must receive broadcast even when paused
        // (focus app is on top when "end focus" is triggered from dashboard)
        val filter = IntentFilter(SkieFocusConstants.ACTION_FOCUS_CHANGED)
        registerReceiver(focusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        // Register package install/remove receiver for launcher grid rebuild
        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, pkgFilter)
        setContent {
            SkiePhoneTheme {
                LauncherScreen(
                    deviceId              = deviceId,
                    focusModeState        = focusModeState,
                    focusAppState         = focusAppState,
                    packageChangeTrigger  = packageChangeTrigger
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ensureServiceRunning()

        // Safety net: focus active but lock task dropped (e.g. after reboot) — re-enter
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val lockTaskActive = am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        if (currentFocusMode != "none" && !lockTaskActive) {
            android.util.Log.w(TAG, "onResume: re-entering lock task — was dropped")
            try { startLockTask() } catch (e: Exception) { android.util.Log.e(TAG, "re-enter lockTask: ${e.message}") }
        }

        // Deep focus back-press recovery:
        // When child presses back from the focus app, launcher surfaces. We must always
        // bounce them straight back into the focus app — no cap on attempts.
        // focusLaunchRecoveryDone is only used for focus_time proxy-bounce (one-shot recovery).
        // For deep_focus: always redirect on every onResume until focus is released.
        if (currentFocusMode == "deep_focus" && !currentFocusApp.isNullOrEmpty()) {
            val focusAppOnTop = isFocusAppOnTop(currentFocusApp!!)
            if (!focusAppOnTop) {
                val appToResume = currentFocusApp!! // capture now — may be nulled before delay fires
                android.util.Log.d(TAG, "onResume: deep_focus back-press — bouncing back to $appToResume")
                mainHandler.postDelayed({
                    // Double-check focus is still active before bouncing — may have been released
                    if (currentFocusMode == "deep_focus" && currentFocusApp == appToResume) {
                        bringFocusTaskToFront(appToResume)
                    }
                }, 200)
            }
        } else if (currentFocusMode == "focus_time" && !currentFocusApp.isNullOrEmpty() && !focusLaunchRecoveryDone) {
            // focus_time proxy-bounce recovery — one-shot only (proxy activity pattern)
            val focusAppOnTop = isFocusAppOnTop(currentFocusApp!!)
            if (!focusAppOnTop) {
                focusLaunchRecoveryDone = true
                val appToResume = currentFocusApp!! // capture now — may be nulled before delay fires
                android.util.Log.d(TAG, "onResume: focus_time proxy-bounce for $appToResume — moveTaskToFront in 400ms")
                mainHandler.postDelayed({
                    if (currentFocusMode == "focus_time" && currentFocusApp == appToResume) {
                        bringFocusTaskToFront(appToResume)
                    }
                }, 400)
            }
        }

        // Apply pending stopLockTask (deferred from when we were in background)
        if (stopLockTaskPending) {
            stopLockTaskPending = false
            android.util.Log.d(TAG, "onResume: applying pending stopLockTask")
            try { stopLockTask() } catch (e: Exception) { android.util.Log.e(TAG, "pending stopLockTask: ${e.message}") }
            repinLauncher()
        }
    }

    override fun onPause() {
        super.onPause()
        // Receiver stays registered — must receive "end focus" broadcast while focus app is on top
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(focusReceiver) } catch (e: Exception) { /* not registered */ }
        try { unregisterReceiver(packageReceiver) } catch (e: Exception) { /* not registered */ }
    }

    // Swallow all back gestures — launcher has no parent to return to.
    // OnBackPressedDispatcher is the only API that intercepts back gestures on Android 13+.
    // The deprecated onBackPressed() override is bypassed entirely by gesture navigation.
    private val backCallback = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // Intentionally do nothing — launcher cannot be backed out of
        }
    }

    /**
     * Initial launch of the focus app via normal intent.
     * For apps with proxy activities (Google Drive etc.), the proxy will run, start
     * the real activity in a singleTask, then finish. The proxy finishing causes the
     * home task (launcher) to surface — this is the "bounce".
     * bringFocusTaskToFront() handles recovery from that bounce.
     */
    private fun launchFocusApp(packageName: String) {
        val intent = this.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (intent != null) {
            startActivity(intent)
            android.util.Log.d(TAG, "launchFocusApp: $packageName")
        } else {
            android.util.Log.e(TAG, "launchFocusApp: no launch intent for $packageName")
        }
    }

    /**
     * Checks if the focus app currently has a task on top of the stack.
     */
    private fun isFocusAppOnTop(focusPackage: String): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val tasks = am.getRunningTasks(1)
        val topPackage = tasks.firstOrNull()?.topActivity?.packageName
        android.util.Log.d(TAG, "isFocusAppOnTop: top=$topPackage expected=$focusPackage")
        return topPackage == focusPackage
    }

    /**
     * Brings the focus app's existing task to front WITHOUT going through its launch intent.
     * This is the robust fix for proxy-activity apps (Google Drive, etc.):
     * - After the proxy finishes, the real activity IS alive in its own task
     * - But the home task surfaced on top of it
     * - moveTaskToFront() directly surfaces the existing task — no proxy re-run
     * Falls back to a fresh intent launch if no existing task is found.
     */
    @Suppress("DEPRECATION")
    private fun bringFocusTaskToFront(focusPackage: String) {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        // Search recent/running tasks for an existing task owned by the focus package
        val recentTasks = am.getRecentTasks(20, ActivityManager.RECENT_IGNORE_UNAVAILABLE)
        val focusTask = recentTasks.firstOrNull { task ->
            task.baseIntent?.component?.packageName == focusPackage ||
                    task.baseIntent?.`package` == focusPackage
        }

        if (focusTask != null) {
            android.util.Log.d(TAG, "bringFocusTaskToFront: moving task ${focusTask.id} ($focusPackage) to front")
            am.moveTaskToFront(focusTask.id, ActivityManager.MOVE_TASK_WITH_HOME)
        } else {
            // No existing task found — app may not have fully started yet, launch fresh
            android.util.Log.d(TAG, "bringFocusTaskToFront: no existing task for $focusPackage — launching fresh")
            launchFocusApp(focusPackage)
        }
    }

    /**
     * Re-pins this launcher as the preferred HOME activity at DPM level.
     * Called after stopLockTask() because Android clears the preferred home app
     * when exiting lock task, which causes the launcher chooser dialog to appear.
     * addPersistentPreferredActivity() operates at Device Owner level and cannot
     * be cleared by lock task exit.
     */
    private fun repinLauncher() {
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, SkieDeviceAdminReceiver::class.java)
            if (!dpm.isDeviceOwnerApp(packageName)) return
            dpm.clearPackagePersistentPreferredActivities(admin, packageName)
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            val launcherComponent = ComponentName(packageName, "$packageName.launcher.LauncherActivity")
            dpm.addPersistentPreferredActivity(admin, filter, launcherComponent)
            android.util.Log.d(TAG, "repinLauncher: launcher re-pinned")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "repinLauncher failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun ensureServiceRunning() {
        // SkiePolicyService.isRunning is a static flag set in service onCreate/onDestroy.
        // Avoids the deprecated ActivityManager.getRunningServices() which is unreliable
        // on Android 12+ and can cause unnecessary service restarts (re-running hardenDevice).
        if (!SkiePolicyService.isRunning) {
            android.util.Log.d(TAG, "ensureServiceRunning: service not running — starting")
            startService(Intent(this, SkiePolicyService::class.java))
        }
    }

    companion object { private const val TAG = "LauncherActivity" }
}

@Composable
fun LauncherScreen(
    deviceId: String,
    focusModeState: MutableState<String>,
    focusAppState: MutableState<String?>,
    packageChangeTrigger: MutableState<Int>
) {
    val context = LocalContext.current
    var allEffectiveApps by remember { mutableStateOf<List<EffectiveApp>>(emptyList()) }
    val travelManager = remember(deviceId) { SkieTravelManager(context, deviceId).also { it.startListening() } }
    val sosManager = remember(deviceId) { SkieSOSManager(context, deviceId, travelManager).also { it.startListening() } }

    val focusMode = focusModeState.value
    val focusApp  = focusAppState.value
    // packageChangeTrigger read here so recomposition fires when it increments
    val pkgTrigger = packageChangeTrigger.value

    // In focus mode: show only the pinned app so grid can't be used to escape
    // pkgTrigger included in key — forces recompute when package installs/removes
    val visibleApps = remember(allEffectiveApps, focusMode, focusApp, pkgTrigger) {
        val pm = context.packageManager
        val base = when {
            focusMode != "none" && !focusApp.isNullOrEmpty() ->
                allEffectiveApps.filter { it.packageName == focusApp }
            else -> allEffectiveApps
        }
        // Re-verify against PackageManager on every trigger — catches installs that happened
        // while effectiveApps RTDB node was unchanged (Day 3 scenario)
        base.filter { app ->
            try {
                pm.getPackageInfo(
                    app.packageName,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                        android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
                    else
                        @Suppress("DEPRECATION") android.content.pm.PackageManager.GET_UNINSTALLED_PACKAGES
                )
                true
            } catch (e: Exception) { false }
        }
    }

    // Listen to effectiveApps from RTDB.
    // Always merges globalPolicy apps (from RTDB cache) with effectiveApps entries.
    // GlobalPolicy apps always visible on launcher regardless of effectiveApps state.
    // If effectiveApps is empty → show globalPolicy only (provisioning state).
    DisposableEffect(deviceId) {
        val rtdbRef = FirebaseDatabase.getInstance().getReference("devices/$deviceId/effectiveApps")
        val globalPolicyCacheRef = FirebaseDatabase.getInstance().getReference("devices/$deviceId/globalPolicyCache")
        val pm = context.packageManager

        // In-memory globalPolicy packages — loaded once and kept fresh
        var globalPolicyApps: List<EffectiveApp> = emptyList()

        fun isInstalled(pkg: String): Boolean = try {
            pm.getPackageInfo(
                pkg,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
                else
                    @Suppress("DEPRECATION") android.content.pm.PackageManager.GET_UNINSTALLED_PACKAGES
            )
            true
        } catch (e: Exception) { false }

        fun rebuildGrid(effectiveSnapshot: DataSnapshot?) {
            val seen = mutableSetOf(context.packageName)
            val apps = mutableListOf<EffectiveApp>()

            // Pass 1 — effectiveApps entries (parent/school approved, allowed=true only)
            effectiveSnapshot?.children?.forEach { child ->
                val pkg  = child.child("packageName").getValue(String::class.java) ?: return@forEach
                val name = child.child("appName").getValue(String::class.java) ?: return@forEach
                val cat  = child.child("category").getValue(String::class.java) ?: ""
                if (pkg == context.packageName) return@forEach
                val rawAllowed = child.child("allowed").value
                val allowed = when (rawAllowed) {
                    is Boolean -> rawAllowed
                    is Long    -> rawAllowed != 0L
                    is String  -> rawAllowed != "false"
                    null       -> true
                    else       -> true
                }
                if (!allowed) return@forEach
                val distractionExpiresAt = child.child("distractionExpiresAt").getValue(Long::class.java) ?: 0L
                if (!isInstalled(pkg)) return@forEach
                seen.add(pkg)
                apps.add(EffectiveApp(pkg, name, cat, distractionExpiresAt))
            }

            // Pass 2 — globalPolicy apps not already shown via effectiveApps
            // These are always visible regardless of effectiveApps state
            globalPolicyApps.forEach { app ->
                if (app.packageName in seen) return@forEach
                if (!isInstalled(app.packageName)) return@forEach
                seen.add(app.packageName)
                apps.add(app)
            }

            android.util.Log.d("LauncherActivity", "rebuildGrid: ${apps.size} apps (${seen.size - 1} seen)")
            allEffectiveApps = apps
        }

        // Load globalPolicy from RTDB cache — fast, works offline, no Firestore auth needed
        // Falls back to Firestore if cache is empty
        fun loadGlobalPolicyCache(onLoaded: () -> Unit) {
            globalPolicyCacheRef.get()
                .addOnSuccessListener { cacheSnap ->
                    if (cacheSnap.exists()) {
                        val pkgs = cacheSnap.children.mapNotNull { it.getValue(String::class.java) }.toSet()
                        // Cache only has packageNames — get display names from PackageManager
                        globalPolicyApps = pkgs.mapNotNull { pkg ->
                            if (pkg == context.packageName) return@mapNotNull null
                            try {
                                val appInfo = pm.getApplicationInfo(pkg, 0)
                                val name = pm.getApplicationLabel(appInfo).toString()
                                EffectiveApp(pkg, name, "essential")
                            } catch (e: Exception) { null }
                        }
                        android.util.Log.d("LauncherActivity", "globalPolicyCache loaded: ${globalPolicyApps.size} apps")
                        onLoaded()
                    } else {
                        // Cache empty — try Firestore directly
                        com.google.firebase.firestore.FirebaseFirestore.getInstance()
                            .document("skieConfig/globalPolicy")
                            .get()
                            .addOnSuccessListener { snap ->
                                val approvedApps = snap.get("approvedApps") as? List<Map<String, Any>> ?: emptyList()
                                globalPolicyApps = approvedApps.mapNotNull { entry ->
                                    val pkg  = entry["packageName"] as? String ?: return@mapNotNull null
                                    val name = entry["appName"] as? String ?: return@mapNotNull null
                                    val cat  = entry["category"] as? String ?: "essential"
                                    if (pkg == context.packageName) return@mapNotNull null
                                    EffectiveApp(pkg, name, cat)
                                }
                                android.util.Log.d("LauncherActivity", "globalPolicy Firestore loaded: ${globalPolicyApps.size} apps")
                                onLoaded()
                            }
                            .addOnFailureListener {
                                android.util.Log.e("LauncherActivity", "globalPolicy load failed: ${it.message}")
                                onLoaded() // proceed with empty globalPolicy rather than hanging
                            }
                    }
                }
                .addOnFailureListener {
                    android.util.Log.e("LauncherActivity", "globalPolicyCache read failed: ${it.message}")
                    onLoaded()
                }
        }

        // Latest effectiveApps snapshot — held so globalPolicy reload can re-merge
        var latestEffectiveSnapshot: DataSnapshot? = null

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                latestEffectiveSnapshot = snapshot
                if (globalPolicyApps.isEmpty()) {
                    // First load — need globalPolicy before we can build the grid
                    loadGlobalPolicyCache { rebuildGrid(snapshot) }
                } else {
                    rebuildGrid(snapshot)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                android.util.Log.e("LauncherActivity", "effectiveApps cancelled: ${error.message}")
            }
        }
        rtdbRef.addValueEventListener(listener)
        onDispose { rtdbRef.removeEventListener(listener) }
    }

    // Listen to lastInstallTs — fallback trigger when a new app installs.
    // Primary trigger is ACTION_PACKAGE_ADDED broadcast (2s delay).
    // This fires after 800ms as secondary safety net.
    // Just increments packageChangeTrigger — visibleApps remember key handles the recompute.
    // Does NOT rebuild allEffectiveApps directly — that is owned by the effectiveApps listener above.
    DisposableEffect(deviceId) {
        val installTsRef = FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/lastInstallTs")
        val installListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    // Trigger visibleApps recompute via packageChangeTrigger
                    // allEffectiveApps is already correct from the effectiveApps listener
                    packageChangeTrigger.value = packageChangeTrigger.value + 1
                    android.util.Log.d("LauncherActivity", "lastInstallTs: triggered grid recompute")
                }, 800)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        installTsRef.addValueEventListener(installListener)
        onDispose { installTsRef.removeEventListener(installListener) }
    }

    // ── Wallpaper — listen to skieConfig/wallpaper, download on change ─────────
    // textScheme: "auto" = luminance detection, "light" = force light text, "dark" = force dark text
    var wallpaperBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // true = use white text, false = use dark text (default when no wallpaper)
    var wallpaperUseLightText by remember { mutableStateOf(false) }
    val wallpaperScope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val ref = FirebaseDatabase.getInstance().getReference("skieConfig/wallpaper")
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (!snap.exists()) {
                    // Wallpaper cleared — reset to gradient + dark text
                    wallpaperBitmap = null
                    wallpaperUseLightText = false
                    return
                }
                val url = snap.child("url").getValue(String::class.java) ?: return
                val textScheme = snap.child("textScheme").getValue(String::class.java) ?: "auto"
                wallpaperScope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        try {
                            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 8000
                            conn.readTimeout = 8000
                            conn.connect()
                            android.graphics.BitmapFactory.decodeStream(conn.inputStream)
                        } catch (e: Exception) {
                            android.util.Log.e("LauncherActivity", "wallpaper download failed: ${e.message}")
                            null
                        }
                    }
                    if (bmp != null) {
                        wallpaperBitmap = bmp
                        wallpaperUseLightText = when (textScheme) {
                            "light" -> true   // owner forced light text
                            "dark"  -> false  // owner forced dark text
                            else    -> withContext(Dispatchers.Default) { computeWallpaperIsDark(bmp) }
                        }
                        android.util.Log.d("LauncherActivity",
                            "wallpaper loaded, textScheme=$textScheme useLightText=$wallpaperUseLightText")
                    }
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        onDispose { ref.removeEventListener(listener) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (wallpaperBitmap == null)
                    Modifier.background(Brush.verticalGradient(colors = listOf(Color(0xFFE8F4FD), Color(0xFFF5F0FF))))
                else
                    Modifier
            )
    ) {
        // Wallpaper image — rendered behind all content when set
        wallpaperBitmap?.let { bmp ->
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(64.dp))
            Text(
                text = "skie",
                style = MaterialTheme.typography.displaySmall,
                color = if (wallpaperUseLightText) Color.White else Color(0xFF1A1A2E)
            )
            Text(
                text = if (focusMode != "none") "focus mode active" else "your safe space",
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    focusMode != "none"     -> Color(0xFF6B4EFF)
                    wallpaperUseLightText   -> Color.White.copy(alpha = 0.75f)
                    else                    -> Color(0xFF8A8AAA)
                }
            )
            Spacer(modifier = Modifier.weight(1f))
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                contentPadding = PaddingValues(bottom = 64.dp, top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Hide Skie Student icon during focus — not an escape route
                if (focusMode == "none") {
                    item { SkieStudentIcon(useLightText = wallpaperUseLightText) }
                }
                items(visibleApps) { app ->
                    EffectiveAppIcon(app = app, useLightText = wallpaperUseLightText)
                }
                // Travel Mode icon — only outside focus mode
                if (focusMode == "none") {
                    item { TravelModeIcon(travelManager = travelManager, useLightText = wallpaperUseLightText) }
                }
                // SOS icon — only outside focus mode
                if (focusMode == "none") {
                    item { SOSIcon(
                        onConfirmed = { endsAt -> sosManager.triggerSOS(endsAt) },
                        onDisable   = { sosManager.clearSOS() },
                        useLightText = wallpaperUseLightText
                    ) }
                }
            }
        }
    }
}

@Composable
fun SkieStudentIcon(useLightText: Boolean = false) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.clickable {
            context.startActivity(Intent(context, SkieStudentActivity::class.java))
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1A1A2E)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "✦", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Skie Student",
            style = MaterialTheme.typography.labelMedium,
            color = if (useLightText) Color.White else Color(0xFF1A1A2E),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun EffectiveAppIcon(app: EffectiveApp, useLightText: Boolean = false) {
    val context = LocalContext.current
    val bitmap = remember(app.packageName) {
        try {
            context.packageManager.getApplicationIcon(app.packageName).toBitmap(width = 128, height = 128)
        } catch (e: Exception) {
            context.packageManager.defaultActivityIcon.toBitmap(width = 128, height = 128)
        }
    }

    // Countdown state — ticks every 30s, shows "Xm left" chip for distraction apps
    var remainingMs by remember(app.packageName, app.distractionExpiresAt) {
        mutableStateOf(
            if (app.distractionExpiresAt > 0L)
                (app.distractionExpiresAt - System.currentTimeMillis()).coerceAtLeast(0L)
            else 0L
        )
    }
    LaunchedEffect(app.packageName, app.distractionExpiresAt) {
        if (app.distractionExpiresAt <= 0L) return@LaunchedEffect
        while (true) {
            val remaining = (app.distractionExpiresAt - System.currentTimeMillis()).coerceAtLeast(0L)
            remainingMs = remaining
            if (remaining <= 0L) break
            kotlinx.coroutines.delay(30_000L) // tick every 30 seconds
        }
    }

    val countdownLabel: String? = remember(remainingMs) {
        if (remainingMs <= 0L) null
        else {
            val mins = (remainingMs / 60_000L).toInt()
            if (mins <= 0) "<1m" else "${mins}m"
        }
    }

    Column(
        modifier = Modifier.clickable {
            context.packageManager.getLaunchIntentForPackage(app.packageName)
                ?.let { context.startActivity(it) }
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(18.dp))
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = app.appName,
                modifier = Modifier.fillMaxSize()
            )
            // Countdown chip — bottom-right of icon
            if (countdownLabel != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                        .background(
                            color = Color(0xCC1A1A2E),
                            shape = RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = countdownLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontSize = androidx.compose.ui.unit.TextUnit(
                            9f, androidx.compose.ui.unit.TextUnitType.Sp
                        )
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = app.appName,
            style = MaterialTheme.typography.labelMedium,
            color = if (useLightText) Color.White else Color(0xFF1A1A2E),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
// ── Wallpaper text scheme — computes luminance from bitmap center region ────
// Returns true if wallpaper is dark (→ use white text)
fun computeWallpaperIsDark(bmp: android.graphics.Bitmap): Boolean {
    val sampleW = minOf(bmp.width, 80)
    val sampleH = minOf(bmp.height, 80)
    val startX = (bmp.width - sampleW) / 2
    val startY = (bmp.height - sampleH) / 2
    var totalLuminance = 0.0
    var count = 0
    for (y in startY until startY + sampleH step 4) {
        for (x in startX until startX + sampleW step 4) {
            val px = bmp.getPixel(x, y)
            val r = android.graphics.Color.red(px) / 255.0
            val g = android.graphics.Color.green(px) / 255.0
            val b = android.graphics.Color.blue(px) / 255.0
            totalLuminance += 0.2126 * r + 0.7152 * g + 0.0722 * b
            count++
        }
    }
    return if (count == 0) false else (totalLuminance / count) < 0.5
}

// ── Duration options — shared by both Travel and SOS ─────────────────────
private val DURATION_OPTIONS = listOf(
    Pair("15m",  15 * 60 * 1000L),
    Pair("30m",  30 * 60 * 1000L),
    Pair("1hr",  60 * 60 * 1000L),
    Pair("2hr",  2 * 60 * 60 * 1000L),
    Pair("8hr",  8 * 60 * 60 * 1000L),
)

// ── Bounce animation — used when Travel/SOS is active ────────────────────
@Composable
fun rememberBounceScale(active: Boolean): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "bounce")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 1.12f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bounceScale"
    )
    return if (active) scale else 1f
}

// ── SOS Icon ──────────────────────────────────────────────────────────────
@Composable
fun SOSIcon(onConfirmed: (endsAt: Long) -> Unit, onDisable: () -> Unit, useLightText: Boolean = false) {
    var showDurationPicker by remember { mutableStateOf(false) }
    var showTurnOffDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val deviceId = remember {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    // Listen to SOS active state
    var sosActive by remember { mutableStateOf(false) }
    var sosEndsAt by remember { mutableStateOf(0L) }
    DisposableEffect(deviceId) {
        val r = FirebaseDatabase.getInstance().getReference("devices/$deviceId/sos")
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                sosActive = snap.child("active").getValue(Boolean::class.java) ?: false
                sosEndsAt = snap.child("endsAt").getValue(Long::class.java) ?: 0L
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        r.addValueEventListener(listener)
        onDispose { r.removeEventListener(listener) }
    }

    // Listen to canChildDisableSOS from policy
    var canDisable by remember { mutableStateOf(true) }
    DisposableEffect(deviceId) {
        val r = FirebaseDatabase.getInstance().getReference("devices/$deviceId/policy/canChildDisableSOS")
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                canDisable = snap.getValue(Boolean::class.java) ?: true
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        r.addValueEventListener(listener)
        onDispose { r.removeEventListener(listener) }
    }

    val bounceScale = rememberBounceScale(sosActive)

    // Duration picker dialog
    if (showDurationPicker) {
        AlertDialog(
            onDismissRequest = { showDurationPicker = false },
            title = { Text("How long should SOS run?", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This will alert your parent, call them, and share your live location.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        DURATION_OPTIONS.forEach { (label, ms) ->
                            Button(
                                onClick = {
                                    showDurationPicker = false
                                    onConfirmed(System.currentTimeMillis() + ms)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF3B30),
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                            ) {
                                Text(label, fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDurationPicker = false }) { Text("Cancel") }
            }
        )
    }

    // Turn off confirmation
    if (showTurnOffDialog) {
        AlertDialog(
            onDismissRequest = { showTurnOffDialog = false },
            title = { Text("Turn off SOS?", fontWeight = FontWeight.SemiBold) },
            text = { Text("Your parent will be notified that SOS has ended.") },
            confirmButton = {
                TextButton(onClick = { showTurnOffDialog = false; onDisable() }) {
                    Text("Turn Off", color = Color(0xFFFF3B30), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTurnOffDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier.clickable {
            if (sosActive) {
                if (canDisable) showTurnOffDialog = true
                // if canDisable=false, do nothing — parent locked this
            } else {
                showDurationPicker = true
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .graphicsLayer(scaleX = bounceScale, scaleY = bounceScale)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFFFF3B30)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "🆘",
                fontSize = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (sosActive) "SOS ON" else "SOS",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFFF3B30),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        if (sosActive && sosEndsAt > 0L) {
            val endTime = remember(sosEndsAt) {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = sosEndsAt }
                String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
            }
            Text(
                text = "until $endTime",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFF3B30).copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

// ── Travel Mode Icon ──────────────────────────────────────────────────────
@Composable
fun TravelModeIcon(travelManager: SkieTravelManager, useLightText: Boolean = false) {
    var showDurationPicker by remember { mutableStateOf(false) }
    var showTurnOffDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val deviceId = remember {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    // Listen to full travelMode node
    var travelActive by remember { mutableStateOf(false) }
    var travelEndsAt by remember { mutableStateOf(0L) }
    DisposableEffect(deviceId) {
        val r = FirebaseDatabase.getInstance().getReference("devices/$deviceId/travelMode")
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                travelActive = snap.child("active").getValue(Boolean::class.java) ?: false
                travelEndsAt = snap.child("endsAt").getValue(Long::class.java) ?: 0L
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        r.addValueEventListener(listener)
        onDispose { r.removeEventListener(listener) }
    }

    // Listen to canChildDisableTravel from policy
    var canDisable by remember { mutableStateOf(true) }
    DisposableEffect(deviceId) {
        val r = FirebaseDatabase.getInstance().getReference("devices/$deviceId/policy/canChildDisableTravel")
        val listener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                canDisable = snap.getValue(Boolean::class.java) ?: true
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        r.addValueEventListener(listener)
        onDispose { r.removeEventListener(listener) }
    }

    val bounceScale = rememberBounceScale(travelActive)

    // Duration picker dialog
    if (showDurationPicker) {
        AlertDialog(
            onDismissRequest = { showDurationPicker = false },
            title = { Text("How long will you be travelling?", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Your parent will be able to track your journey live for this duration.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        DURATION_OPTIONS.forEach { (label, ms) ->
                            Button(
                                onClick = {
                                    showDurationPicker = false
                                    val endsAt = System.currentTimeMillis() + ms
                                    FirebaseDatabase.getInstance()
                                        .getReference("devices/$deviceId/travelMode")
                                        .updateChildren(mapOf(
                                            "active" to true,
                                            "triggeredBy" to "student",
                                            "startedAt" to System.currentTimeMillis(),
                                            "endsAt" to endsAt
                                        ))
                                    travelManager.startListening()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF6366F1),
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                            ) {
                                Text(label, fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDurationPicker = false }) { Text("Cancel") }
            }
        )
    }

    // Turn off confirmation
    if (showTurnOffDialog) {
        AlertDialog(
            onDismissRequest = { showTurnOffDialog = false },
            title = { Text("End Travel Mode?", fontWeight = FontWeight.SemiBold) },
            text = { Text("Your parent will be notified that travel mode has ended.") },
            confirmButton = {
                TextButton(onClick = {
                    showTurnOffDialog = false
                    FirebaseDatabase.getInstance()
                        .getReference("devices/$deviceId/travelMode")
                        .updateChildren(mapOf("active" to false, "disabledAt" to System.currentTimeMillis()))
                }) {
                    Text("End Travel", color = Color(0xFF6366F1), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTurnOffDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier.clickable {
            if (travelActive) {
                if (canDisable) showTurnOffDialog = true
                // if canDisable=false, locked by parent — do nothing
            } else {
                showDurationPicker = true
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .graphicsLayer(scaleX = bounceScale, scaleY = bounceScale)
                .clip(RoundedCornerShape(18.dp))
                .background(if (travelActive) Color(0xFF6366F1) else Color(0xFFEEEEFF)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "🧭",
                fontSize = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (travelActive) "Travelling" else "Travel",
            style = MaterialTheme.typography.labelMedium,
            color = if (travelActive) Color(0xFF6366F1) else if (useLightText) Color.White else Color(0xFF1A1A2E),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (travelActive && travelEndsAt > 0L) {
            val endTime = remember(travelEndsAt) {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = travelEndsAt }
                String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
            }
            Text(
                text = "until $endTime",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF6366F1).copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}