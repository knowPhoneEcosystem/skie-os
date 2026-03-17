package com.skiephone.app.launcher

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

data class EffectiveApp(
    val packageName: String = "",
    val appName: String = "",
    val category: String = ""
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
        ensureServiceRunning()
        // Register for full activity lifetime — must receive broadcast even when paused
        // (focus app is on top when "end focus" is triggered from dashboard)
        val filter = IntentFilter(SkieFocusConstants.ACTION_FOCUS_CHANGED)
        registerReceiver(focusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        setContent {
            SkiePhoneTheme {
                LauncherScreen(
                    deviceId       = deviceId,
                    focusModeState = focusModeState,
                    focusAppState  = focusAppState
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

        // Proxy-bounce recovery: some apps (e.g. Google Drive) use a proxy/splash Activity
        // that finishes itself after starting the real activity in a singleTask.
        // When the proxy finishes, the home task (launcher) surfaces even though the real
        // activity task IS alive — it's just buried beneath the home task.
        //
        // Re-launching via intent goes through the proxy AGAIN → same bounce.
        // Instead: find the existing task for the focus app and call moveTaskToFront().
        // This surfaces the already-running real activity directly — no proxy involved.
        // focusLaunchRecoveryDone caps this at one attempt per focus session.
        if (currentFocusMode != "none" && !currentFocusApp.isNullOrEmpty() && !focusLaunchRecoveryDone) {
            val focusAppOnTop = isFocusAppOnTop(currentFocusApp!!)
            if (!focusAppOnTop) {
                focusLaunchRecoveryDone = true  // set BEFORE to prevent re-entry
                android.util.Log.d(TAG, "onResume: proxy-bounce for $currentFocusApp — attempting moveTaskToFront in 400ms")
                mainHandler.postDelayed({ bringFocusTaskToFront(currentFocusApp!!) }, 400)
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
    }

    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        // Swallow back — launcher has no parent
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
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == SkiePolicyService::class.java.name }
        if (!running) startService(Intent(this, SkiePolicyService::class.java))
    }

    companion object { private const val TAG = "LauncherActivity" }
}

@Composable
fun LauncherScreen(
    deviceId: String,
    focusModeState: MutableState<String>,
    focusAppState: MutableState<String?>
) {
    val context = LocalContext.current
    var allEffectiveApps by remember { mutableStateOf<List<EffectiveApp>>(emptyList()) }
    val travelManager = remember(deviceId) { SkieTravelManager(context, deviceId).also { it.startListening() } }
    val sosManager = remember(deviceId) { SkieSOSManager(context, deviceId, travelManager).also { it.startListening() } }

    val focusMode = focusModeState.value
    val focusApp  = focusAppState.value

    // In focus mode: show only the pinned app so grid can't be used to escape
    val visibleApps = remember(allEffectiveApps, focusMode, focusApp) {
        when {
            focusMode != "none" && !focusApp.isNullOrEmpty() ->
                allEffectiveApps.filter { it.packageName == focusApp }
            else -> allEffectiveApps
        }
    }

    // Listen to effectiveApps from RTDB.
    // If empty → fall back to globalPolicy from Firestore (filtered to installed apps).
    DisposableEffect(deviceId) {
        val rtdbRef = FirebaseDatabase.getInstance().getReference("devices/$deviceId/effectiveApps")
        val pm = context.packageManager

        fun appsFromSnapshot(snapshot: DataSnapshot): List<EffectiveApp> {
            val apps = mutableListOf<EffectiveApp>()
            snapshot.children.forEach { child ->
                val pkg  = child.child("packageName").getValue(String::class.java) ?: return@forEach
                val name = child.child("appName").getValue(String::class.java) ?: return@forEach
                val cat  = child.child("category").getValue(String::class.java) ?: ""
                if (pkg == context.packageName) return@forEach
                try { pm.getPackageInfo(pkg, 0); apps.add(EffectiveApp(pkg, name, cat)) }
                catch (e: Exception) { /* not installed, skip */ }
            }
            return apps
        }

        fun loadGlobalPolicyFallback() {
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .document("skieConfig/globalPolicy")
                .get()
                .addOnSuccessListener { snap ->
                    val approvedApps = snap.get("approvedApps") as? List<Map<String, Any>> ?: emptyList()
                    val apps = approvedApps.mapNotNull { entry ->
                        val pkg  = entry["packageName"] as? String ?: return@mapNotNull null
                        val name = entry["appName"] as? String ?: return@mapNotNull null
                        val cat  = entry["category"] as? String ?: "other"
                        if (pkg == context.packageName) return@mapNotNull null
                        try { pm.getPackageInfo(pkg, 0); EffectiveApp(pkg, name, cat) }
                        catch (e: Exception) { null }
                    }
                    android.util.Log.d("LauncherActivity", "globalPolicy fallback: ${apps.size} apps")
                    allEffectiveApps = apps
                }
                .addOnFailureListener {
                    android.util.Log.e("LauncherActivity", "globalPolicy load failed: ${it.message}")
                }
        }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists() || snapshot.childrenCount == 0L) {
                    android.util.Log.d("LauncherActivity", "effectiveApps empty — loading globalPolicy fallback")
                    loadGlobalPolicyFallback()
                    return
                }
                val apps = appsFromSnapshot(snapshot)
                android.util.Log.d("LauncherActivity", "effectiveApps: ${apps.size} apps")
                allEffectiveApps = apps
            }
            override fun onCancelled(error: DatabaseError) {
                android.util.Log.e("LauncherActivity", "effectiveApps cancelled: ${error.message}")
            }
        }
        rtdbRef.addValueEventListener(listener)
        onDispose { rtdbRef.removeEventListener(listener) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(Color(0xFFE8F4FD), Color(0xFFF5F0FF))))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(64.dp))
            Text(
                text = "skie",
                style = MaterialTheme.typography.displaySmall,
                color = Color(0xFF1A1A2E)
            )
            Text(
                text = if (focusMode != "none") "focus mode active" else "your safe space",
                style = MaterialTheme.typography.bodyMedium,
                color = if (focusMode != "none") Color(0xFF6B4EFF) else Color(0xFF8A8AAA)
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
                    item { SkieStudentIcon() }
                }
                items(visibleApps) { app ->
                    EffectiveAppIcon(app = app)
                }
                // Travel Mode icon — only outside focus mode
                if (focusMode == "none") {
                    item { TravelModeIcon(travelManager = travelManager) }
                }
                // SOS icon — only outside focus mode
                if (focusMode == "none") {
                    item { SOSIcon(onConfirmed = { sosManager.triggerSOS() }) }
                }
            }
        }
    }
}

@Composable
fun SkieStudentIcon() {
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
            color = Color(0xFF1A1A2E),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun EffectiveAppIcon(app: EffectiveApp) {
    val context = LocalContext.current
    val bitmap = remember(app.packageName) {
        try {
            context.packageManager.getApplicationIcon(app.packageName).toBitmap(width = 128, height = 128)
        } catch (e: Exception) {
            context.packageManager.defaultActivityIcon.toBitmap(width = 128, height = 128)
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
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = app.appName,
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF1A1A2E),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
// ── SOS Icon ──────────────────────────────────────────────────────────────
@Composable
fun SOSIcon(onConfirmed: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Send SOS?", fontWeight = FontWeight.SemiBold) },
            text = { Text("This will immediately alert your parent, call them, and share your live location.") },
            confirmButton = {
                TextButton(onClick = { showDialog = false; onConfirmed() }) {
                    Text("Yes, send SOS", color = Color(0xFFFF3B30), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier.clickable { showDialog = true },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFFFF3B30)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "🆘", fontSize = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "SOS",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFFF3B30),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

// ── Travel Mode Icon ──────────────────────────────────────────────────────
@Composable
fun TravelModeIcon(travelManager: SkieTravelManager) {
    var showDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val deviceId = remember {
        android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        )
    }

    // Listen to current travel mode state
    var travelActive by remember { mutableStateOf(false) }
    DisposableEffect(deviceId) {
        val ref = com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/travelMode/active")
        val listener = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snap: com.google.firebase.database.DataSnapshot) {
                travelActive = snap.getValue(Boolean::class.java) ?: false
            }
            override fun onCancelled(e: com.google.firebase.database.DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        onDispose { ref.removeEventListener(listener) }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Enable Travel Mode?", fontWeight = FontWeight.SemiBold) },
            text = { Text("Your parent will be able to track your journey live until you arrive at your destination.") },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    // Write travelMode active=true to RTDB — parent sets destination from dashboard
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference("devices/$deviceId/travelMode")
                        .updateChildren(mapOf(
                            "active" to true,
                            "triggeredBy" to "student",
                            "startedAt" to System.currentTimeMillis()
                        ))
                    travelManager.startListening()
                }) {
                    Text("Enable", color = Color(0xFF6366F1), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier.clickable { if (!travelActive) showDialog = true },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
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
            color = if (travelActive) Color(0xFF6366F1) else Color(0xFF1A1A2E),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}