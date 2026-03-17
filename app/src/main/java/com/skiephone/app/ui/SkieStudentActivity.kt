package com.skiephone.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.skiephone.app.BuildConfig
import com.skiephone.app.services.SkieTravelManager
import com.skiephone.app.ui.theme.SkiePhoneTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

class SkieStudentActivity : SkieBaseActivity() {

    private var deviceId: String = ""
    private lateinit var travelManager: SkieTravelManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        travelManager = SkieTravelManager(this, deviceId)
        travelManager.startListening()
        setContent {
            SkiePhoneTheme {
                SkieStudentScreen(
                    deviceId = deviceId,
                    context = this,
                    onBack = { finish() }
                )
            }
        }
    }
}

fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SkieStudentScreen(deviceId: String, context: Context, onBack: () -> Unit) {
    var showQr by remember { mutableStateOf(false) }
    var focusMode by remember { mutableStateOf("none") }
    var focusApp by remember { mutableStateOf<String?>(null) }
    var focusIssuedBy by remember { mutableStateOf<String?>(null) }
    var showFocusAnimation by remember { mutableStateOf(false) }
    var prevFocusMode by remember { mutableStateOf("none") }

    // WebView state
    var webUrl by remember { mutableStateOf<String?>(null) }
    var urlLoading by remember { mutableStateOf(true) }
    var isOnline by remember { mutableStateOf(isNetworkAvailable(context)) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    // ── Load URL from Firestore skieConfig/appConfig ──────────
    LaunchedEffect(Unit) {
        try {
            val doc = FirebaseFirestore.getInstance()
                .collection("skieConfig")
                .document("appConfig")
                .get()
                .await()
            val url = doc.getString("studentWebUrl")
                ?: "https://skiephone.com/student/login"
            webUrl = url
        } catch (e: Exception) {
            webUrl = "https://skiephone.com/student/login"
        } finally {
            urlLoading = false
        }
    }

    // ── Listen to focus mode from RTDB ────────────────────────
    LaunchedEffect(deviceId) {
        val policyRef = FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/policy")
        policyRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newMode = snapshot.child("focusMode").getValue(String::class.java) ?: "none"
                val newApp = snapshot.child("focusApp").getValue(String::class.java)
                val newIssuedBy = snapshot.child("focusIssuedBy").getValue(String::class.java)
                if (prevFocusMode == "none" && newMode != "none") {
                    showFocusAnimation = true
                }
                prevFocusMode = newMode
                focusMode = newMode
                focusApp = newApp
                focusIssuedBy = newIssuedBy
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // ── Handle hardware back — navigate WebView history ───────
    BackHandler(enabled = canGoBack) {
        webViewRef?.goBack()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF8F9FA))) {

        when {
            urlLoading -> {
                // Loading URL from Firestore
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF1A1A2E)
                    )
                }
            }

            !isOnline -> {
                // ── No internet screen ────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(text = "📡", fontSize = 56.sp)
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "No Internet Connection",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF1A1A2E),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Please connect to Wi-Fi or mobile data, then tap Retry to open the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF888888),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = { isOnline = isNetworkAvailable(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A2E)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Retry", color = Color.White)
                    }
                }
            }

            else -> {
                // ── Main WebView layout ───────────────────────
                Column(modifier = Modifier.fillMaxSize()) {

                    // Top bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        // Back button — only show if can go back in WebView
                        if (canGoBack) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1A1A2E).copy(alpha = 0.06f))
                                    .clickable { webViewRef?.goBack() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "←", fontSize = 16.sp, color = Color(0xFF1A1A2E))
                            }
                        }

                        // Title
                        Text(
                            text = "skie student",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF1A1A2E),
                            modifier = Modifier.align(Alignment.Center)
                        )

                        // Right side: reload + QR
                        Row(
                            modifier = Modifier.align(Alignment.CenterEnd),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Reload button
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1A1A2E).copy(alpha = 0.06f))
                                    .clickable { webViewRef?.reload() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "↻", fontSize = 18.sp, color = Color(0xFF1A1A2E))
                            }
                            // QR button
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1A1A2E).copy(alpha = 0.06f))
                                    .clickable { showQr = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "⊞", fontSize = 16.sp, color = Color(0xFF1A1A2E))
                            }
                        }
                    }

                    // Focus mode banner — shown above WebView when active
                    if (focusMode != "none") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = if (focusMode == "deep_focus") Color(0xFF1A1A2E) else Color(0xFF2D5FA6)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (focusMode == "deep_focus") "🧘 Deep Focus active" else "🎯 Focus Time active",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White
                                )
                                focusIssuedBy?.let {
                                    Text(
                                        text = "by $it",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // ── WebView ───────────────────────────────
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    loadWithOverviewMode = true
                                    useWideViewPort = true
                                    setSupportZoom(false)
                                    builtInZoomControls = false
                                    displayZoomControls = false
                                    // Allow mixed content for PWA compatibility
                                    @Suppress("DEPRECATION")
                                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                }

                                // Block address bar changes — intercept all navigation
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        // Allow navigation within skiephone.com only
                                        val url = request?.url?.toString() ?: return false
                                        return if (url.contains("skiephone.com") || url.startsWith("https://skiephone.web.app")) {
                                            false // allow
                                        } else {
                                            true // block external links
                                        }
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        canGoBack = view?.canGoBack() ?: false
                                    }
                                }

                                webChromeClient = WebChromeClient()

                                webViewRef = this
                                webUrl?.let { loadUrl(it) }
                            }
                        },
                        update = { wv ->
                            webViewRef = wv
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }

        // ── Deep Focus animation overlay ──────────────────────
        if (showFocusAnimation) {
            FocusActivationAnimation(
                focusMode = focusMode,
                issuedBy = focusIssuedBy,
                onDone = { showFocusAnimation = false }
            )
        }

        // ── QR overlay ────────────────────────────────────────
        if (showQr) {
            StudentQrOverlay(deviceId = deviceId, onDismiss = { showQr = false })
        }
    }
}

@Composable
fun FocusActivationAnimation(
    focusMode: String,
    issuedBy: String?,
    onDone: () -> Unit
) {
    val isDeep = focusMode == "deep_focus"
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
        delay(2800)
        visible = false
        delay(300)
        onDone()
    }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(300),
        label = "alpha"
    )

    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.85f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isDeep) Color(0xFF0A0A1A).copy(alpha = alpha * 0.97f)
                else Color(0xFF1A3A6A).copy(alpha = alpha * 0.97f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .scale(scale)
                .padding(40.dp)
        ) {
            Text(text = if (isDeep) "🧘" else "🎯", fontSize = 64.sp)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = if (isDeep) "Deep Focus" else "Focus Time",
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xFFFFFFFF),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (isDeep) "You're locked in.\nStay with it. 💪"
                else "Time to focus.\nYou've got this. ✨",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFFFFFFF).copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                lineHeight = 26.sp
            )
            issuedBy?.let {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Started by $it",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFFFFFFF).copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun StudentQrOverlay(deviceId: String, onDismiss: () -> Unit) {
    val qrBitmap = remember(deviceId) {
        try {
            BarcodeEncoder().encodeBitmap(deviceId, BarcodeFormat.QR_CODE, 400, 400)
        } catch (e: Exception) { null }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF080808).copy(alpha = 0.95f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Text(
                    text = "skie",
                    style = MaterialTheme.typography.displaySmall,
                    color = Color(0xFFFFFFFF)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Scan to pair this device",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF555555)
                )
                Spacer(modifier = Modifier.height(40.dp))
                qrBitmap?.let {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFFFFFFF),
                        modifier = Modifier.size(240.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Device QR",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = deviceId,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFFFFFFF),
                    textAlign = TextAlign.Center,
                    letterSpacing = 3.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF333333)
                )
                Spacer(modifier = Modifier.height(40.dp))
                Text(
                    text = "Tap anywhere to close",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF444444)
                )
            }
        }
    }
}